package app.centsible

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.centsible.core.assistant.AssistantAgent
import app.centsible.core.assistant.TurnEnded
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal const val ASSISTANT_WORK_CHANNEL = "assistant_work"
internal const val ASSISTANT_CHANNEL = "assistant"
private const val WORKING_ID = 7_301

/** "assistant:<chat id>" opens that chat (see MainScaffold). */
internal fun assistantLink(conversationId: String) = "assistant:$conversationId"

/** Whether any of the app's screens is showing, so a finished turn only notifies when you've left. */
object AppVisibility : Application.ActivityLifecycleCallbacks {
    @Volatile private var started = 0
    val visible get() = started > 0

    override fun onActivityStarted(activity: Activity) { started++ }
    override fun onActivityStopped(activity: Activity) { started = (started - 1).coerceAtLeast(0) }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

/**
 * Keeps the assistant going when you leave the app: while a turn is under way a foreground
 * service holds the app up (Android would otherwise freeze it and cut the connection to
 * Claude), and when a turn ends while you're away, a notification says how it went.
 */
@Singleton
class AssistantBackground @Inject constructor(private val agent: AssistantAgent) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start(context: Context) {
        createChannels(context)
        scope.launch {
            agent.busy.collect { ids ->
                // Started while you're in the app (you just sent or approved something), so Android allows it.
                if (ids.isNotEmpty()) runCatching { ContextCompat.startForegroundService(context, Intent(context, AssistantWorkService::class.java)) }
            }
        }
        scope.launch { agent.turns.collect { if (!AppVisibility.visible) notifyEnded(context, it) } }
    }

    private fun notifyEnded(context: Context, e: TurnEnded) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val (title, text) = when (e) {
            is TurnEnded.Replied -> "Assistant replied" to plain(e.text)
            is TurnEnded.NeedsApproval -> (if (e.changes == 1) "A change to approve" else "${e.changes} changes to approve") to "The assistant is waiting for you in “${e.title}”."
            is TurnEnded.Failed -> "The assistant stopped" to e.message
        }
        val n = NotificationCompat.Builder(context, ASSISTANT_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(e.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openChat(context, e.conversationId))
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission") // checked with areNotificationsEnabled above
        manager.notify(e.conversationId.hashCode(), n)
    }

    companion object {
        /** The reply without Markdown marks, cut to a notification's length. */
        internal fun plain(markdown: String): String = markdown
            .replace(Regex("""\*\*|__|`|^#+\s*|^\s*[-*•]\s+|^\s*>\s?""", RegexOption.MULTILINE), "")
            .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
            .lines().filter { it.isNotBlank() && !it.trim().matches(Regex("""^\|?[\s:-]*\|[\s:|-]*$""")) }
            .joinToString(" ") { line ->
                val t = line.trim()
                // A table row reads as "a · b".
                if (t.startsWith("|")) t.split('|').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" · ") else t
            }
            .let { if (it.length > 400) it.take(397).trimEnd() + "…" else it }

        fun openChat(context: Context, conversationId: String): PendingIntent = PendingIntent.getActivity(
            context, conversationId.hashCode(),
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN, assistantLink(conversationId)).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(ASSISTANT_WORK_CHANNEL, "Assistant working", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Shown while the assistant finishes a reply with the app closed" },
            )
            manager.createNotificationChannel(
                NotificationChannel(ASSISTANT_CHANNEL, "Assistant replies", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "When the assistant answers or needs your approval while you're away" },
            )
        }
    }
}

/** Runs while the assistant has a turn under way, and stops itself when none is left. */
@AndroidEntryPoint
class AssistantWorkService : Service() {
    @Inject lateinit var agent: AssistantAgent
    private var scope: CoroutineScope? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android needs it straight away, but shows it only if the turn runs past ten seconds or so.
        val first = working(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(WORKING_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(WORKING_ID, first)
        if (scope == null) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { s ->
                s.launch {
                    agent.busy.collect { ids ->
                        if (ids.isEmpty()) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        } else {
                            val title = if (ids.size == 1) agent.title(ids.first()) else null
                            val text = title?.let { "Working on “$it”" } ?: "Working on ${ids.size} chats"
                            NotificationManagerCompat.from(this@AssistantWorkService).let { m ->
                                @Suppress("MissingPermission")
                                if (m.areNotificationsEnabled()) m.notify(WORKING_ID, working(text, ids.singleOrNull()))
                            }
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android stops a data-sync service after its daily allowance: let the turn go rather than crash. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    private fun working(text: String?, conversationId: String? = null): Notification =
        NotificationCompat.Builder(this, ASSISTANT_WORK_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Assistant is working")
            .setContentText(text ?: "Finishing your reply")
            .setOngoing(true)
            .setSilent(true)
            .apply { conversationId?.let { setContentIntent(AssistantBackground.openChat(this@AssistantWorkService, it)) } }
            .build()
}

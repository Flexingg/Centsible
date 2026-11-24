package app.centsible.feature.assistant

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import app.centsible.core.assistant.AssistantSettings
import app.centsible.core.assistant.ChangeStatus
import app.centsible.core.assistant.ChatItem
import app.centsible.core.assistant.ConversationSummary
import app.centsible.core.assistant.LiveBlock
import app.centsible.core.assistant.LiveTurn
import app.centsible.core.assistant.MemoryNote
import app.centsible.core.assistant.ProposedChange
import app.centsible.core.assistant.StreamUpdate
import app.centsible.core.assistant.ToolCall
import app.centsible.core.assistant.ToolGroup
import app.centsible.core.designsystem.theme.CentsibleTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class AssistantScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val reply = """
        You've spent **$1,284.40** of **$1,860.00** in Food this month, and you're 18 days in, so you're a little **ahead of pace**.

        | Category | Spent | Budgeted |
        |---|---|---|
        | Groceries | $612.20 | $900.00 |
        | Dining Out | $345.80 | $300.00 |
        | Coffee | $21.50 | $60.00 |

        Dining Out is already over. Two options:
        - Move **$60** from Coffee, which won't need it.
        - Or trim Groceries for the rest of the month.
    """.trimIndent()

    private val proposed = ProposedChange(
        "t1", "move_money", "budget", "Move money · September 2026",
        listOf("$60.00 from Coffee to Dining Out"), "{}",
    )
    private val done = ProposedChange(
        "t2", "set_transaction_categories", "transactions", "Set categories on 3 transactions",
        listOf("2026-09-14 Costco -$84.12: none → Groceries › Costco", "2026-09-12 Shell -$41.00: none → Gas", "2026-09-11 Blue Bottle -$6.50: none → Coffee"),
        "{}", status = ChangeStatus.Done, outcome = "Categorized 3 transactions", undo = ToolCall("set_transaction_categories", "{}"),
    )

    private val chat = ChatUiState(
        conversationId = "c1",
        title = "How's Food going this month?",
        items = listOf(
            ChatItem.User("1", "How's Food going this month?"),
            ChatItem.Thinking("2", "Read the month's budget and recent dining spend."),
            ChatItem.Lookup("3", "get_budget_month", "Reading the budget", false, null),
            ChatItem.Lookup("4", "search_transactions", "Looking through transactions", false, null),
            ChatItem.Reply("5", reply),
            ChatItem.User("6", "Yes, move it from Coffee. And categorize the three that are waiting."),
            ChatItem.Change("7", done),
            ChatItem.Change("8", proposed),
        ),
        hasKey = true,
        settings = AssistantSettings(writable = setOf(ToolGroup.Budget, ToolGroup.Transactions)),
    )

    private fun shot(name: String, dark: Boolean = false, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { CentsibleTheme(darkTheme = dark) { content() } }
        compose.onRoot().captureRoboImage("screenshots/$name.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Test fun assistant_chat_light() = shot("assistant_chat_light") { AssistantChatScreen(chat, ChatActions()) }

    @Test fun assistant_chat_working_dark() = shot("assistant_chat_working_dark", dark = true) {
        AssistantChatScreen(
            chat.copy(items = chat.items.take(1), live = LiveTurn(running = true, activity = "Running reports…", blocks = listOf(LiveBlock(0, StreamUpdate.Kind.Thinking, "Comparing with the last three months.")))),
            ChatActions(),
        )
    }

    @Test fun assistant_welcome() = shot("assistant_welcome") { AssistantChatScreen(ChatUiState(hasKey = true), ChatActions()) }

    @Test fun assistant_needs_key() = shot("assistant_needs_key") { AssistantChatScreen(ChatUiState(hasKey = false), ChatActions()) }

    @Test fun assistant_error() = shot("assistant_error") {
        AssistantChatScreen(chat.copy(items = chat.items.take(1), live = LiveTurn(error = "Your API key hit its rate limit. Wait a minute and try again.", retryable = true)), ChatActions())
    }

    @Test fun assistant_settings() = shot("assistant_settings") {
        AssistantSettingsScreen(
            AssistantSettingsUiState(
                settings = AssistantSettings(writable = setOf(ToolGroup.Transactions, ToolGroup.Budget)),
                hasKey = true,
                notes = listOf(MemoryNote("m1", "Saving for a house deposit by mid-2028.", 0, 0), MemoryNote("m2", "Jo is paid every other Friday.", 1, 1)),
            ),
            AssistantSettingsActions(),
        )
    }

    @Test fun assistant_chats() = shot("assistant_chats") {
        AssistantChatsScreen(
            listOf(
                ConversationSummary("a", "Plan next month's budget", System.currentTimeMillis() - 5 * 60_000, awaitingApproval = true),
                ConversationSummary("b", "How fast can I pay off the Visa?", System.currentTimeMillis() - 26 * 3_600_000, awaitingApproval = false),
            ),
            {}, {}, {}, {},
        )
    }

    @Test fun `approving and declining go to the right change`() {
        var approved: String? = null
        var declined: String? = null
        compose.setContent { CentsibleTheme { AssistantChatScreen(chat, ChatActions(approve = { approved = it }, decline = { declined = it })) } }
        compose.onNode(androidx.compose.ui.test.hasScrollToNodeAction()).performScrollToNode(androidx.compose.ui.test.hasText("Approve"))
        compose.onNodeWithText("Approve").performClick()
        compose.onNodeWithText("Decline").performClick()
        assertEquals("t1", approved)
        assertEquals("t1", declined)
    }

    @Test fun `markdown splits into blocks`() {
        val blocks = Md.parse(reply)
        assertEquals(listOf("Paragraph", "Table", "Paragraph", "Bullets"), blocks.map { it::class.simpleName })
        val table = blocks[1] as MdBlock.Table
        assertEquals(listOf("Category", "Spent", "Budgeted"), table.header)
        assertEquals(3, table.rows.size)
    }
}

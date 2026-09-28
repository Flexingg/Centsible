package app.centsible.core.designsystem.component

import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role

/**
 * Makes a whole row (label + Switch or Checkbox) one toggle. TalkBack then reads the
 * label with the state ("Cleared, switch, on") and the entire row is the tap target.
 * Give the Switch/Checkbox inside `onCheckedChange = null`.
 */
fun Modifier.toggleRow(checked: Boolean, enabled: Boolean = true, role: Role = Role.Switch, onChange: (Boolean) -> Unit): Modifier =
    toggleable(value = checked, enabled = enabled, role = role, onValueChange = onChange)

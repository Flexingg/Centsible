package app.centsible.core.domain

import app.centsible.core.model.BudgetId
import app.centsible.core.model.Device
import app.centsible.core.model.DeviceId
import app.centsible.core.model.Member
import app.centsible.core.model.MemberId
import app.centsible.core.model.PairingInvite
import app.centsible.core.model.PairingLink
import app.centsible.core.model.Role

interface HouseholdGateway {
    suspend fun me(): Me
    suspend fun members(): List<Member>
    suspend fun addMember(displayName: String, role: Role, budgets: List<BudgetId>): Member
    suspend fun invite(member: MemberId): PairingInvite
    suspend fun setBudgets(member: MemberId, budgets: List<BudgetId>): Member
    suspend fun revokeDevice(device: DeviceId)
    suspend fun logout()
}

data class Me(val member: Member, val device: Device, val devices: List<Device>)

interface PairingGateway {
    /** Redeems a pairing code; returns a session ready to be stored. */
    suspend fun pair(link: PairingLink, deviceName: String): Session
}

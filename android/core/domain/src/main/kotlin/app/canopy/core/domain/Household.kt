package app.canopy.core.domain

import app.canopy.core.model.BudgetId
import app.canopy.core.model.Device
import app.canopy.core.model.DeviceId
import app.canopy.core.model.Member
import app.canopy.core.model.MemberId
import app.canopy.core.model.PairingInvite
import app.canopy.core.model.PairingLink
import app.canopy.core.model.Role

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

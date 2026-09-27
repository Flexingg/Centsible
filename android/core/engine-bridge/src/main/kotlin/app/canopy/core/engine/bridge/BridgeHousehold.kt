package app.canopy.core.engine.bridge

import app.canopy.core.domain.HouseholdGateway
import app.canopy.core.domain.Me
import app.canopy.core.domain.PairingGateway
import app.canopy.core.domain.Session
import app.canopy.core.model.BudgetId
import app.canopy.core.model.DeviceId
import app.canopy.core.model.MemberId
import app.canopy.core.model.PairingInvite
import app.canopy.core.model.PairingLink
import app.canopy.core.model.Role
import app.canopy.core.network.BridgeApi
import app.canopy.core.network.NewMemberDto
import app.canopy.core.network.PairRequestDto
import javax.inject.Inject

class BridgeHouseholdGateway @Inject constructor(private val api: BridgeApi) : HouseholdGateway {
    override suspend fun me() = api.me().let { Me(it.member.toModel(), it.device.toModel(), it.devices.map { d -> d.toModel() }) }
    override suspend fun members() = api.members().map { it.toModel() }
    override suspend fun addMember(displayName: String, role: Role, budgets: List<BudgetId>) =
        api.createMember(NewMemberDto(displayName, role.wire(), budgets.map { it.raw })).toModel()
    override suspend fun invite(member: MemberId) = api.createPairingCode(member.raw).let { PairingInvite(it.code, it.expiresAt, it.pairingUri) }
    override suspend fun setBudgets(member: MemberId, budgets: List<BudgetId>) = api.setMemberBudgets(member.raw, budgets.map { it.raw }).toModel()
    override suspend fun revokeDevice(device: DeviceId) = api.revokeDevice(device.raw)
    override suspend fun logout() = api.logout()
}

class BridgePairingGateway @Inject constructor(private val api: BridgeApi) : PairingGateway {
    override suspend fun pair(link: PairingLink, deviceName: String): Session {
        val res = api.pair(link.bridgeUrl, link.cfAccessClientId, link.cfAccessClientSecret, PairRequestDto(link.code, deviceName))
        return Session(
            bridgeUrl = link.bridgeUrl,
            accessToken = res.accessToken,
            refreshToken = res.refreshToken,
            cfAccessClientId = link.cfAccessClientId,
            cfAccessClientSecret = link.cfAccessClientSecret,
            member = res.member.toModel(),
            deviceId = DeviceId(res.device.id),
        )
    }
}

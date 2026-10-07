// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto.aa

import dev.zanderp.opencfmoto.TurnHaptics
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus

/**
 * Navigation-status channel (ID_NAV), only advertised when [TurnHaptics.enabled]. Android Auto sends
 * the next manoeuvre + distance here for an instrument cluster; we turn it into watch vibrations.
 *
 * Two generations of the protocol exist and AA may use either:
 *  - old: NEXTTURNDETAILS (manoeuvre + side + roundabout exit) + NEXTTURNDISTANCEANDTIME
 *  - new: INSTRUMENT_CLUSTER_NAVIGATION_STATE (steps) + ..._CURRENT_POSITION (distance to step)
 */
internal class AapControlNav : AapControl {

    override fun execute(message: AapMessage): Int {
        try {
            when (message.type) {
                NavigationStatus.MsgType.INSTRUMENT_CLUSTER_START_VALUE -> TurnHaptics.onNavActive(true)
                NavigationStatus.MsgType.INSTRUMENT_CLUSTER_STOP_VALUE -> TurnHaptics.onNavActive(false)
                NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATUS_VALUE -> {
                    val s = message.parse(NavigationStatus.NavigationClusterStatus.newBuilder()).build()
                    TurnHaptics.onNavActive(s.status == NavigationStatus.NavigationClusterStatus.NavigationStatusEnum.ACTIVE)
                }
                NavigationStatus.MsgType.NEXTTURNDETAILS_VALUE -> {
                    val d = message.parse(NavigationStatus.NextTurnDetail.newBuilder()).build()
                    TurnHaptics.onManeuver(fromOld(d), if (d.hasTurnNumber()) d.turnNumber else 0, d.road)
                }
                NavigationStatus.MsgType.NEXTTURNDISTANCEANDTIME_VALUE -> {
                    val e = message.parse(NavigationStatus.NextTurnDistanceEvent.newBuilder()).build()
                    TurnHaptics.onDistance(
                        e.distanceMeters,
                        if (e.hasTimeToTurnSeconds()) e.timeToTurnSeconds else -1,
                    )
                }
                NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATE_VALUE -> {
                    val st = message.parse(NavigationStatus.NavigationState.newBuilder()).build()
                    val step = st.stepsList.firstOrNull()
                    if (step == null) {
                        TurnHaptics.onManeuver(TurnHaptics.Kind.NONE, 0, "")
                    } else {
                        val m = step.maneuver
                        TurnHaptics.onManeuver(
                            fromNew(m.type),
                            if (m.hasRoundaboutExitNumber()) m.roundaboutExitNumber else 0,
                            if (step.hasRoad()) step.road.name else "",
                        )
                    }
                }
                NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_CURRENT_POSITION_VALUE -> {
                    val p = message.parse(NavigationStatus.NavigationCurrentPosition.newBuilder()).build()
                    if (p.hasStepDistance()) {
                        val sd = p.stepDistance
                        TurnHaptics.onDistance(
                            if (sd.hasDistance()) sd.distance.meters else -1,
                            if (sd.hasTimeToStepSeconds()) sd.timeToStepSeconds.toInt() else -1,
                        )
                    }
                }
                else -> AaLog.i("NAV: unhandled msg type %d", message.type)
            }
        } catch (e: Exception) {
            AaLog.e("NAV: parse failed for type ${message.type}: ${e.message}")
        }
        return 0
    }

    private fun fromOld(d: NavigationStatus.NextTurnDetail): TurnHaptics.Kind {
        val left = d.side == NavigationStatus.NextTurnDetail.Side.LEFT
        val right = d.side == NavigationStatus.NextTurnDetail.Side.RIGHT
        fun lr(slight: Boolean) = when {
            left -> if (slight) TurnHaptics.Kind.SLIGHT_LEFT else TurnHaptics.Kind.LEFT
            right -> if (slight) TurnHaptics.Kind.SLIGHT_RIGHT else TurnHaptics.Kind.RIGHT
            else -> TurnHaptics.Kind.NONE
        }
        return when (d.nextTurn) {
            NavigationStatus.NextTurnDetail.NextEvent.TURN,
            NavigationStatus.NextTurnDetail.NextEvent.SHARP_TURN -> lr(false)
            NavigationStatus.NextTurnDetail.NextEvent.SLIGHT_TURN,
            NavigationStatus.NextTurnDetail.NextEvent.ON_RAMP,
            NavigationStatus.NextTurnDetail.NextEvent.OFFRAMP,
            NavigationStatus.NextTurnDetail.NextEvent.FORK,
            NavigationStatus.NextTurnDetail.NextEvent.MERGE -> lr(true)
            NavigationStatus.NextTurnDetail.NextEvent.U_TURN -> TurnHaptics.Kind.UTURN
            NavigationStatus.NextTurnDetail.NextEvent.ROUNDABOUT_ENTER,
            NavigationStatus.NextTurnDetail.NextEvent.ROUNDABOUT_ENTER_AND_EXIT -> TurnHaptics.Kind.ROUNDABOUT
            NavigationStatus.NextTurnDetail.NextEvent.DESTINATION -> TurnHaptics.Kind.DESTINATION
            else -> TurnHaptics.Kind.NONE // straight, depart, name change, roundabout exit, ferries
        }
    }

    private fun fromNew(t: NavigationStatus.NavigationManeuver.NavigationType): TurnHaptics.Kind {
        val n = t.name
        return when {
            n.startsWith("ROUNDABOUT_ENTER") -> TurnHaptics.Kind.ROUNDABOUT
            n.startsWith("U_TURN") -> TurnHaptics.Kind.UTURN
            n == "DESTINATION" -> TurnHaptics.Kind.DESTINATION
            n.startsWith("TURN_SLIGHT") || n.startsWith("KEEP") || n.startsWith("ON_RAMP") ||
                n.startsWith("OFF_RAMP") || n.startsWith("FORK") || n.startsWith("MERGE") ->
                when {
                    n.endsWith("_LEFT") -> TurnHaptics.Kind.SLIGHT_LEFT
                    n.endsWith("_RIGHT") -> TurnHaptics.Kind.SLIGHT_RIGHT
                    else -> TurnHaptics.Kind.NONE
                }
            n.startsWith("TURN_") -> when {
                n.endsWith("_LEFT") -> TurnHaptics.Kind.LEFT
                n.endsWith("_RIGHT") -> TurnHaptics.Kind.RIGHT
                else -> TurnHaptics.Kind.NONE
            }
            else -> TurnHaptics.Kind.NONE
        }
    }
}

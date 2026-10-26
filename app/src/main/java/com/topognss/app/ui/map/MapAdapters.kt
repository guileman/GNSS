package com.topognss.app.ui.map

import androidx.compose.ui.graphics.Color
import com.topognss.app.data.PointEntity
import com.topognss.app.data.PointKind
import com.topognss.app.data.pointKind
import com.topognss.app.gnss.ConnectionStatus
import com.topognss.app.gnss.GnssState
import com.topognss.app.ui.theme.FixGreen
import com.topognss.app.ui.theme.solutionColor

fun kindColor(k: PointKind): Color = when (k) {
    PointKind.SURVEY -> Color(0xFF0E7C86)
    PointKind.COGO -> Color(0xFFB45309)
    PointKind.STAKE -> Color(0xFF7C3AED)
    PointKind.STAKED -> FixGreen
    PointKind.GCP -> Color(0xFFD81B60)
}

fun PointEntity.toMapPoint(order: Int? = null) = MapPoint(id, name, lat, lon, kindColor(pointKind()), order)

fun GnssState.toMapMe(): MapMe? {
    val la = lat ?: return null
    val lo = lon ?: return null
    if (connection != ConnectionStatus.CONNECTED) return null
    return MapMe(la, lo, solutionColor(solution), hrms)
}

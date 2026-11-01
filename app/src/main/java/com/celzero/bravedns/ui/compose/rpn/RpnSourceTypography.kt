package com.celzero.bravedns.ui.compose.rpn

import androidx.annotation.DimenRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.celzero.bravedns.ui.compose.utilities.utilityDimensionSp

/** Keep the resource qualifier and raw SP value; nonlinear font scaling is applied exactly once. */
@Composable internal fun rpnDimensionSp(@DimenRes id: Int): TextUnit =
    utilityDimensionSp(LocalContext.current, id).sp

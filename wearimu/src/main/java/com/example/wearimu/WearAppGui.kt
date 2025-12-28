package com.example.wearimu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material.*
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 1. THE PREVIEW WRAPPER
// This function has NO parameters, so the Preview tool knows how to run it.
@Preview(
    device = Devices.WEAR_OS_SMALL_ROUND,
    showSystemUi = true,
    backgroundColor = 0xff000000,
    showBackground = true
)
@Composable
fun WearAppPreview() {
    MaterialTheme {
        WearApp(
            linearText = "Acc: X=12.20, Y=-12.502, Z=02.21",
            rotationText = "Rot: X=180.0, Y=0.0, Z=0.0, W=1.0",
            isConnected = true
        )
    }
}


@Composable
fun WearApp(linearText: String, rotationText: String, isConnected: Boolean) {
    Scaffold(
        vignette = { Vignette(vignettePosition = VignettePosition.Bottom) }
    ) {
        // Use a Box to layer the TimeText on top of the Column
        Box(modifier = Modifier.fillMaxSize()) {

            // 1. The TimeText (Anchors to the top automatically)
            TimeText()

            // 2. The Main Content (Centered in the Box)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.primary,
                    text = "Connected: $isConnected",
                    fontSize = 10.sp,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = linearText,
                    color = MaterialTheme.colors.onPrimary,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.caption2,
                    fontSize = 10.sp,
                )
                Text(
                    text = rotationText,
                    color = MaterialTheme.colors.onPrimary,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.caption2,
                    fontSize = 10.sp,
                )

                //Spacer(modifier = Modifier.height(10.dp))

                Canvas(
                    modifier = Modifier
                        .size(80.dp)
                        .padding(4.dp),
                ) {
                    val center = Offset(size.width / 2, size.height / 2)
                    val radius = size.minDimension / 2

                    drawCircle(
                        color = if (isConnected) Color.Green else Color.Red,
                        center = center,
                        radius = radius,
                        style = Stroke(width = 2.dp.toPx())
                    )

                    // Logic to extract values for visualization
                    val linearX = linearText.substringAfter("Acc: X=").substringBefore(",").toFloatOrNull() ?: 0f
                    val linearY = linearText.substringAfter("Y=").substringBefore(",").toFloatOrNull() ?: 0f

                    if (linearX != 0f || linearY != 0f) {
                        // We scale the line so it stays inside the circle
                        val endX = center.x + (linearX * 2)
                        val endY = center.y + (linearY * 2)

                        drawLine(
                            color = Color.Cyan,
                            start = center,
                            end = Offset(endX, endY),
                            strokeWidth = 3.dp.toPx()
                        )
                    }
                }
            }
        }
    }
}
package com.tombad8900.cinemastreamnative

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Gold = Color(0xFFE6B96F)
private val Muted = Color(0xFF9BA5B5)
private val Background = Color(0xFF080B12)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CinemaStreamApp()
        }
    }
}

@Composable
private fun CinemaStreamApp() {
    val labels = listOf("الرئيسية", "استكشاف", "مكتبتي", "IPTV", "الإعدادات")
    val symbols = listOf("⌂", "⌕", "♡", "▣", "⚙")
    var selectedTab by remember { mutableIntStateOf(0) }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Gold,
                background = Background,
                surface = Color(0xFF151B29),
                onBackground = Color.White,
                onSurface = Color.White
            )
        ) {
            Scaffold(
                containerColor = Background,
                bottomBar = {
                    NavigationBar(
                        containerColor = Color(0xFF101521)
                    ) {
                        labels.forEachIndexed { index, label ->
                            NavigationBarItem(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                icon = {
                                    Text(symbols[index], fontSize = 19.sp)
                                },
                                label = {
                                    Text(label, fontSize = 10.sp)
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Gold,
                                    selectedTextColor = Gold,
                                    unselectedIconColor = Muted,
                                    unselectedTextColor = Muted,
                                    indicatorColor = Color(0xFF30291F)
                                )
                            )
                        }
                    }
                }
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                ) {
                    Spacer(Modifier.height(26.dp))

                    Text(
                        "C I N E M A S T R E A M",
                        color = Gold,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(12.dp))

                    Text(
                        labels[selectedTab],
                        color = Color.White,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(8.dp))

                    if (selectedTab == 0) {
                        HomeContent {
                            selectedTab = 1
                        }
                    } else {
                        Text(
                            "هذا القسم ضمن مراحل البناء القادمة.",
                            color = Muted,
                            fontSize = 15.sp
                        )
                    }

                    Spacer(Modifier.height(28.dp))
                }
            }
        }
    }
}

@Composable
private fun HomeContent(onExplore: () -> Unit) {
    Text(
        "عالمك السينمائي في مكان واحد",
        color = Muted,
        fontSize = 15.sp
    )

    Spacer(Modifier.height(24.dp))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(225.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0xFF49351F),
                        Color(0xFF202638),
                        Color(0xFF101521)
                    )
                )
            )
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    "YOUR CINEMA",
                    color = Gold,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "حكايتك القادمة\nتبدأ من هنا",
                    color = Color.White,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 36.sp
                )
            }

            Button(
                onClick = onExplore,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Gold,
                    contentColor = Color(0xFF17120B)
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("ابدأ الاستكشاف", fontWeight = FontWeight.Bold)
            }
        }
    }

    Spacer(Modifier.height(30.dp))

    Text(
        "اكتشف مكتبتك",
        color = Color.White,
        fontSize = 21.sp,
        fontWeight = FontWeight.Bold
    )

    Spacer(Modifier.height(14.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CategoryCard("الأفلام", "عالم من الحكايات", Modifier.weight(1f))
        CategoryCard("المسلسلات", "تابع قصتك", Modifier.weight(1f))
    }

    Spacer(Modifier.height(12.dp))

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF151B29),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                "تصميم جديد من الصفر",
                color = Gold,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "هذه هي النواة الأولى للتطبيق الأصلي. ستضاف المكتبة والمشغل والمتصفح كوحدات مستقلة.",
                color = Muted,
                fontSize = 13.sp,
                lineHeight = 21.sp
            )
        }
    }
}

@Composable
private fun CategoryCard(
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color(0xFF151B29),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(7.dp))
            Text(description, color = Muted, fontSize = 12.sp)
        }
    }
}

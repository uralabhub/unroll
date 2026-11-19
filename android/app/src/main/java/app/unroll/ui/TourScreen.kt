package app.unroll.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.unroll.AppState
import app.unroll.data.Quota
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** One page of the feature tour: a picture, what it's for, and how to do it. */
private class TourPage(
    @DrawableRes val icon: Int,
    val kicker: String,
    val title: String,
    val body: String,
    val tips: List<Pair<Int, String>>,
)

/** Every feature, in the order people meet them: get photos in, pick, import, then the extras. */
private val tourPages = listOf(
    TourPage(Ph.camera, "Welcome to Unroll", "From camera to posted, in seconds",
        "Unroll pulls photos straight off your camera, keeps the good ones, puts them on the map and frames them for sharing. Here's the one‑minute tour.",
        listOf(Ph.lock to "No account, no cloud. Everything stays on your phone.",
            Ph.checkCircle to "Your RAW files and recipes are never touched.",
            Ph.forward to "Swipe to continue, or skip any time.")),
    TourPage(Ph.usb, "1 · Connect", "Plug in your camera",
        "Use a USB‑C cable. No pairing, no Bluetooth. If Android asks, allow Unroll to use the camera. The newest photos show up first while the rest load.",
        listOf(Ph.sd to "Got a card reader? Tap Open an SD card on Home.",
            Ph.wifi to "Ricoh GR III / IIIx: Settings › Connections › Wi‑Fi.",
            Ph.play to "No camera handy? Try the demo roll on Home.")),
    TourPage(Ph.images, "2 · Browse", "Find the keepers fast",
        "Stars and protect marks you set on the camera come across, so your picks are already sorted.",
        listOf(Ph.star to "Tabs: All · Not imported · Picks (rated or protected on the camera).",
            Ph.filter to "Filter button: by stars, protected, RAW or shot today.",
            Ph.info to "Tap a photo to see it full size. Long‑press for Open, Import, Select.",
            Ph.more to "The ⋯ menu has tile size, recap card and every tool.")),
    TourPage(Ph.download, "3 · Import", "Into your gallery in one tap",
        "Import your picks or everything new from the bar at the bottom. Unroll remembers what's already imported, so nothing comes in twice.",
        listOf(Ph.checkCircle to "Tap Select to choose by hand, or select a whole day at once.",
            Ph.copy to "JPG and RAW are saved side by side in DCIM/Unroll, full resolution.",
            Ph.plug to "Unplugged mid-import? It pauses and carries on when you plug back in.",
            Ph.sliders to "Settings › When a camera connects: import automatically.")),
    TourPage(Ph.film, "4 · Recipes", "See how every shot was made",
        "Open a photo and tap Info for exposure, lens and the film recipe: Fujifilm film simulations, Nikon Picture Control, Sony Creative Style.",
        listOf(Ph.recipes to "Recipes collect in your Recipe book as you import.",
            Ph.share to "Share a recipe as a card, or copy it as text.")),
    TourPage(Ph.walk, "5 · Location", "Put photos on the map",
        "Your camera doesn't need GPS or Bluetooth. Unroll adds the location when it imports.",
        listOf(Ph.play to "Tap Start on Photo walk before you head out, Stop when you're done.",
            Ph.pin to "Photos taken along the way get the spot where you stood.",
            Ph.images to "No walk? It borrows the location of phone photos taken within 20 minutes.",
            Ph.path to "Past routes live in Walk history.")),
    TourPage(Ph.timer, "6 · Clock sync", "Fix the camera's clock",
        "A camera clock that drifts or sits in the wrong time zone puts photos in the wrong place. Fix it once per camera.",
        listOf(Ph.qr to "Open Clock sync and photograph the code on screen with your camera.",
            Ph.usb to "Connect the camera.",
            Ph.checkCircle to "Tap Read from camera. Every import is corrected from then on.")),
    TourPage(Ph.frame, "7 · Share", "Frames and recap cards",
        "Frame Studio adds a clean border with your settings or recipe: six styles, four ratios, full resolution.",
        listOf(Ph.frame to "Tap Frame on any photo, or Frame a photo on Home for pictures already on your phone.",
            Ph.images to "Recap card: up to 6 photos and your walk's route, 9:16 for stories. From ⋯ or Select › Recap.",
            Ph.share to "Save to your gallery or share straight from the editor.")),
    TourPage(Ph.crown, "Free & Pro", "Free to start",
        "See everything on the card, with EXIF and recipes, and import ${Quota.FREE_PER_DAY} photos a day for free.",
        listOf(Ph.crown to "Pro: unlimited imports, auto-import of picks, every frame style, no watermark.",
            Ph.question to "Replay this tour any time from the ? on Home or from Settings.")),
)

/** The feature tour: full screen over everything. First launch, then from Home's ? or Settings. */
@Composable
fun TourScreen(app: AppState) {
    val pager = rememberPagerState { tourPages.size }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val last = pager.currentPage == tourPages.lastIndex
    fun finish() { app.settings.hasOnboarded = true; app.showTour = false }

    BackHandler {
        if (pager.currentPage > 0) scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } else finish()
    }
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.drop(1).collect { Haptics.tick(view) } }

    Column(Modifier.fillMaxSize().background(Mono.c.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${pager.currentPage + 1} / ${tourPages.size}", Modifier.weight(1f), style = Mono.data(13.sp), color = Mono.c.dim)
            AnimatedVisibility(!last, enter = fadeIn(), exit = fadeOut()) {
                Text("Skip", Modifier.clickable(onClickLabel = "Skip the tour") { finish() }.padding(8.dp),
                    style = Mono.body.copy(fontWeight = FontWeight.Medium), color = Mono.c.dim)
            }
        }

        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { i ->
            TourPageContent(tourPages[i], active = pager.settledPage == i)
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 14.dp).semantics { contentDescription = "Page ${pager.currentPage + 1} of ${tourPages.size}" },
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(tourPages.size) { i ->
                val on = i == pager.currentPage
                val w by animateDpAsState(if (on) 18.dp else 6.dp, Motion.snap(), label = "dot")
                Box(Modifier.padding(horizontal = 3.dp).width(w).height(6.dp).background(if (on) Mono.c.accent else Mono.c.faint, CircleShape)
                    .clickable { scope.launch { pager.animateScrollToPage(i) } })
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp).animateContentSize(Motion.float()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (last) {
                PrimaryButton("Try the demo roll", Modifier.fillMaxWidth()) { app.openDemo(); finish() }
                SecondaryButton("Start using Unroll", Modifier.fillMaxWidth()) { finish() }
            } else {
                PrimaryButton(if (pager.currentPage == 0) "Show me around" else "Next", Modifier.fillMaxWidth()) {
                    scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                }
            }
        }
    }
}

@Composable
private fun TourPageContent(page: TourPage, active: Boolean) {
    val reduce = LocalReduceMotion.current
    val heroScale by animateFloatAsState(if (active || reduce) 1f else 0.86f, Motion.float(), label = "heroScale")
    val tipsIn by animateFloatAsState(if (active || reduce) 1f else 0f, tween(420, delayMillis = if (active) 120 else 0), label = "tips")

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 28.dp).graphicsLayer { scaleX = heroScale; scaleY = heroScale }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(168.dp).background(Mono.c.accent.copy(alpha = 0.10f), CircleShape))
                Box(Modifier.size(124.dp).border(1.dp, Mono.c.accent.copy(alpha = 0.25f), CircleShape))
                PhIcon(page.icon, null, size = 56.dp, tint = Mono.c.accent)
            }
            Text(page.kicker.uppercase(), style = Mono.footnote.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp), color = Mono.c.accent)
            Text(page.title, Modifier.padding(top = 6.dp).semantics { heading() }, style = Mono.title.copy(fontSize = 28.sp, lineHeight = 34.sp), color = Mono.c.ink)
            Text(page.body, Modifier.padding(top = 10.dp), style = Mono.body, color = Mono.c.dim)

            Column(
                Modifier.padding(top = 22.dp).fillMaxWidth().background(Mono.c.surface, Mono.shape).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                page.tips.forEachIndexed { i, (icon, text) ->
                    val k = ((tipsIn * (page.tips.size + 1)) - i).coerceIn(0f, 1f)
                    Row(Modifier.graphicsLayer { alpha = k; translationY = (1 - k) * 12.dp.toPx() }, verticalAlignment = Alignment.Top) {
                        PhIcon(icon, null, Modifier.padding(top = 1.dp), size = 20.dp, tint = Mono.c.ink)
                        Spacer(Modifier.width(14.dp))
                        Text(text, style = Mono.subhead, color = Mono.c.ink)
                    }
                }
            }
        }
    }
}

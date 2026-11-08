
CINEMASTREAM NATIVE - ENGINEERING REVIEW

Project:
Android application built with Kotlin and Jetpack Compose.

Review scope:
1. BrowserScreen and WebView integration.
2. MainActivity navigation and browser tab lifecycle.
3. Stream detection and classification.
4. HLS, DASH and direct video stream detection.
5. Subtitle detection, especially Arabic subtitles.
6. Media3 native player integration and handoff.
7. HTTP headers, Referer, Origin, User-Agent and cookies.
8. Popup and advertisement handling.
9. Session isolation and capture lifecycle.
10. Security, DRM boundaries and privacy.

Current reported problems:
- Browser tab sometimes displays a completely black screen.
- Switching from movie/series details to Browser tab does not
  navigate until Back is pressed.
- Browser and capture implementation are still preliminary.

Required behavior:
- Visible browser with manual user interaction.
- Continuous capture while browsing.
- Detect direct playable streams and subtitle resources.
- Do not classify images as subtitles based only on extensions.
- Hand off compatible direct streams to CinemaStream Media3 player.
- Preserve IPTV, TMDB, library and existing player features.
- Do not bypass DRM or protected access controls.

Questions for reviewers:
A. Identify concrete causes of the black screen.
B. Review Compose navigation and WebView lifecycle.
C. Evaluate the current architecture and propose improvements.
D. Evaluate detection accuracy and response/MIME inspection options.
E. Review headers and cookie handling.
F. Identify security and privacy weaknesses.
G. Recommend a phased implementation plan with minimal regressions.
H. Distinguish technically feasible features from unsupported ones.

Please cite exact files and functions for each finding.
Do not recommend replacing working application features without
a demonstrated technical reason.

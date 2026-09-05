# MediaPipe-koordinater til præcis geometrisk analyse og overlay af karatebevægelser

## Den vigtigste konklusion

Der er to fundamentalt forskellige problemer i dit workflow, og de bør holdes helt adskilt:

1. **At tegne et eksisterende MediaPipe-landmark på den frame, der faktisk blev analyseret.**  
   Det er i princippet ligetil: MediaPipes normaliserede `x` og `y` er knyttet til inputbilledet, og de konverteres til pixels med billedets faktiske bredde og højde. MediaPipes egen drawing utility gør i praksis `floor(x * width)` og `floor(y * height)` med clipping ved den sidste pixel. citeturn19view0turn20view2

2. **At tage et nyt punkt, som du selv har beregnet i MediaPipes 3D/world-koordinater, og finde dets præcise pixelposition i kameraets 2D-frame.**  
   Det kan **ikke** gøres eksakt alene ud fra standard-outputtet fra Pose Landmarker. Pose world landmarks er 3D-punkter i meter med hoftecentrum som lokal oprindelse, men MediaPipe leverer ikke den fulde kameratransformation, der mapper dette world-system tilbage til billedplanet. En inspektion af MediaPipes aktuelle source graph er særlig afslørende: de normaliserede billedlandmarks bliver roteret, skaleret og translateret fra den interne ROI tilbage til hele inputbilledet, mens `WorldLandmarkProjectionCalculator` kun roterer world-landmarks i `x/y`; den foretager ingen kameraperspektivprojektion og ingen translation ind i kameraets koordinatsystem. citeturn21view1turn20view3turn21view2

Det betyder, at denne kæde:

\[
\text{shoulderWorld},\text{wristWorld}
\rightarrow
P_\text{new}^{3D}
\rightarrow
?
\rightarrow
(u,v)
\]

mangler et vigtigt led. For en fysisk perspektivprojektion skal du have eller estimere:

\[
P_\text{camera}=K[R|t]
\]

og eventuelt også modellere linsedistorsion. OpenCV beskriver præcis denne projektion som

\[
s
\begin{bmatrix}
u\\v\\1
\end{bmatrix}
=
K[R|t]
\begin{bmatrix}
X\\Y\\Z\\1
\end{bmatrix}.
\]

Her er `K` kameraets intrinsics, mens `R,t` placerer MediaPipes lokale 3D-krop i kameraets koordinatsystem. citeturn17view0turn17view1

For din karate-analyzer er den robuste arkitektur derfor:

> **Brug image landmarks til rendering af observerede punkter. Brug world landmarks til biomekaniske 3D-beregninger. Projekter kun selvberegnede 3D-punkter til billedet, hvis du eksplicit har estimeret en world→camera→image-model.**

Det er den skelnen, der forhindrer størstedelen af de klassiske overlay-fejl.

## Hvad MediaPipes koordinatsystemer faktisk betyder

### Pose: normaliserede image landmarks

Pose Landmarker returnerer 33 landmarks både som `Landmarks` og `WorldLandmarks`. De almindelige landmarks har:

\[
x_n,\;y_n,\;z_n
\]

hvor `x` og `y` er normaliseret med henholdsvis inputbilledets bredde og højde. Google beskriver dem som værdier normaliseret til intervallet `[0,1]`. `z` er derimod en relativ dybdeværdi med hofternes midpoint som nulpunkt; mindre `z` betyder nærmere kameraet, og størrelsesordenen er omtrent den samme som `x`. citeturn19view0

Geometrisk skal du tænke dem således:

\[
x_n \approx \frac{u}{W}, \qquad
y_n \approx \frac{v}{H}.
\]

Det er et **billedkoordinatsystem**, ikke et fysisk 3D-kamerasystem.

`x=0` svarer til venstre billedkant og `x=1` til højre billedkant; `y=0` svarer til toppen og `y=1` til bunden i den pixelorientering, som MediaPipe faktisk blev givet. MediaPipes drawing implementation multiplicerer netop `x` med antallet af kolonner og `y` med antallet af rækker. citeturn20view2

Pose-modellens model card forklarer også, hvad der foregår længere nede i modellen. Landmark-netværket arbejder internt på en krops-ROI, der er aligned/cropped til et `256×256` input. Det rå `z` er i en billedrelativ skala, med hofteplanet som reference; negative værdier ligger mod kameraet fra hofteplanet, positive bagved. Det er altså ikke fysisk kameradybde i meter. citeturn20view7

Det er vigtigt, fordi et punkt som

```text
(xNormalized, yNormalized, zNormalized)
```

**ikke** må behandles som en almindelig Euclidean 3D-vektor, hvor alle tre koordinater er i samme fysiske coordinate frame. `x/y` repræsenterer billedplacering, mens `z` er en modelestimeret relativ dybde.

### Pose: world landmarks

Pose WorldLandmarks er anderledes. Den aktuelle MediaPipe-dokumentation beskriver:

\[
(X_w,Y_w,Z_w)
\]

som 3D-koordinater **i meter**, med midpoint mellem hofterne som origin. citeturn19view0

Det gør dem særdeles anvendelige til:

- 3D-ledvinkler,
- retning mellem skulder og håndled,
- relativ dybde mellem kropsdele,
- 3D-afvigelser,
- projektion af en vektor på en anatomisk plan,
- beregning af idealiserede punkter og retninger.

BlazePose-modelkortet angiver direkte 3D pose measurements såsom vinkler og afstande som en tiltænkt anvendelse. Samme modelkort sætter dog applikationer, der kræver **metric accurate depth**, uden for modellens scope. Med andre ord: enheden er meter, men det betyder ikke, at du har fået en kalibreret måling af, at f.eks. knoen befinder sig præcis `2.438 m` foran telefonens sensor. citeturn20view8

Den forskel er central:

| Egenskab | Pose image landmark | Pose world landmark |
|---|---|---|
| `x,y` | Billedplacering | 3D-kropskoordinat |
| `z` | Relativ billeddybde | 3D-kropskoordinat |
| Enhed | Normaliseret/billedrelativ | Meter |
| Origin | Billedets øverste venstre for `x,y`; hofte-midpoint for `z` | Hofte-midpoint |
| Direkte pixelkonvertering | Ja | Nej |
| Perspektivprojektion indeholdt | Allerede repræsenteret i `x,y` | Nej |
| God til 3D-ledvinkler | Begrænset | Ja |
| God til direkte rendering | Ja | Kun efter registrering/projektion |

En særlig vigtig detalje er, at MediaPipes Tasks-dokumentation **ikke definerer Pose world frame som et komplet kalibreret OpenCV-kamerakoordinatsystem** med kendte intrinsics/extrinsics. OpenCVs kameraframe har eksempelvis eksplicit `+X` mod højre, `+Y` nedad og `+Z` fremad. Det er en definition, du ikke uden videre må påføre Pose WorldLandmarks. citeturn17view0

MediaPipe-sourcekoden bekræfter forskellen. `WorldLandmarkProjectionCalculator` tager world coordinates og korrigerer kun `x/y` for ROI'ens rotation:

\[
X'=\cos\theta X-\sin\theta Y
\]

\[
Y'=\sin\theta X+\cos\theta Y.
\]

Der tilføjes hverken personens position i billedet, kameratranslation eller perspektivdivision med dybden. citeturn20view3

Det er stærkt teknisk bevis for, at WorldLandmarks **ikke er image-projectable camera coordinates ud af boksen**.

### Hand Landmarker

Hand Landmarker følger samme overordnede todeling, men har andre origins.

For image landmarks returnerer MediaPipe 21 punkter, hvor:

\[
x,y\in[0,1]
\]

er normaliseret med billedets bredde/højde, og `z` er relativ dybde med **håndleddet som z-origin**. Mindre `z` er nærmere kameraet. citeturn21view0

Hand WorldLandmarks er derimod 21 tredimensionelle punkter **i meter**, men her er origin håndens **geometriske center**, ikke hofterne. citeturn21view0 Modelkortet beskriver ligeledes outputtet som 21 “metric scale world landmarks”, baseret på GHUM-håndmodellen. citeturn20view9

Det har en direkte konsekvens for din karate-applikation:

> Du må ikke skrive noget i stil med  
> `poseShoulderWorld - handIndexWorld`.

Selv om begge tal angives i meter, tilhører de ikke samme origin. Du skal først registrere håndens lokale coordinate frame til kroppens coordinate frame.

Det samme gælder for retninger: forskellige origins kan håndteres med translation, men du bør heller ikke antage identisk akseorientering uden at have etableret transformationen.

En praktisk løsning, hvis Hand Landmarker bruges til f.eks. kno- og håndledsorientering, er at udføre **rendering i image coordinates** og kun anvende Hand WorldLandmarks til intern lokal 3D-håndgeometri.

Hand Landmarker returnerer desuden eksplicit en Left/Right handedness classification. citeturn21view0 Det må ikke forveksles med, om det billede, du viser brugeren, er spejlet. Mirroring af pixelbufferen er en separat geometrisk transformation.

### Face Landmarker

Face Landmarker bør behandles som et tredje coordinate-system, ikke som en fortsættelse af Pose WorldLandmarks.

Den aktuelle Face Landmarker kan returnere 478 3D facial landmarks samt valgfri facial transformation matrices. Transformationen relaterer en canonical face model til det detekterede ansigt og er især tiltænkt rendering/effects. citeturn19view2turn20view1

Face Mesh-modelkortet beskriver ansigtslandmarks således:

- `x/y` følger image pixel coordinates,
- `z` er relativ til ansigtets center of mass,
- `z` skaleres proportionalt med ansigtets bredde. citeturn20view10

Det er altså **ikke** et Pose WorldLandmarks-system med hofterne som origin.

For dit eksempel med “estimated chin” er den bedste løsning ofte derfor:

- Find hagen direkte som Face image landmark, hvis Face Landmarker er tilgængelig.
- Brug dens `x/y` direkte til rendering.
- Registrer kun face 3D til Pose 3D, hvis du faktisk behøver én fælles 3D-model.

At blande:

```text
PoseWorld.shoulder
FaceLandmark.chin
HandWorld.knuckle
```

som om de var tre punkter i samme Cartesian space, er geometrisk forkert.

## Fra normaliserede landmarks til de rigtige pixels

Hvis MediaPipe analyserede et billede med:

```text
analysisWidth  = W
analysisHeight = H
```

og returnerede:

```text
landmark.x = xn
landmark.y = yn
```

er de kontinuerte billedkoordinater:

\[
u=x_nW
\]

\[
v=y_nH.
\]

MediaPipes egen Python drawing utility konverterer til en integer pixel med:

\[
u_i=\min(\lfloor x_n W\rfloor,W-1)
\]

\[
v_i=\min(\lfloor y_n H\rfloor,H-1).
\]

Den afviser også et normaliseret punkt, hvis `x` eller `y` ligger uden for det gyldige normaliserede interval. citeturn20view2

Det betyder blandt andet, at den ofte sete formel

```text
pixelX = normalizedX * (width - 1)
```

ikke er den formel, MediaPipes officielle drawing implementation bruger. Den bruger `x * width` efterfulgt af floor/clamping. citeturn20view2

Til Canvas/OpenCV-tegning med floating-point primitives ville jeg bevare:

```kotlin
val x = landmark.x() * analysisWidth
val y = landmark.y() * analysisHeight
```

som floats så længe som muligt og først lade rasterizeren afgøre, hvilke pixels stregen/cirklen rammer.

### Den afgørende regel: brug MediaPipe-inputtets dimensioner

Antag, at din originale videoframe er:

```text
1920 × 1080
```

men du selv laver:

```text
crop
→ rotate
→ resize to 720 × 1280
→ MediaPipe
```

Så gælder:

```text
xPixel = landmark.x * 720
yPixel = landmark.y * 1280
```

i **MediaPipes input coordinate frame**, ikke nødvendigvis i den originale `1920×1080` frame.

Derefter skal punktet transformeres tilbage til den frame, hvor du vil tegne.

Dette bør være en eksplicit del af din softwarearkitektur.

Definér eksempelvis:

```text
S = saved/source frame
A = exact MediaPipe analysis image
V = PreviewView
```

og gem transformationerne:

\[
T_{S\rightarrow A}
\]

\[
T_{A\rightarrow S}=T_{S\rightarrow A}^{-1}
\]

\[
T_{A\rightarrow V}
\]

som separate matricer.

For et MediaPipe landmark:

\[
p_A=
\begin{bmatrix}
x_nW_A\\
y_nH_A\\
1
\end{bmatrix}
\]

og det punkt, der skal tegnes på din gemte frame, er:

\[
p_S
\sim
T_{A\rightarrow S}p_A.
\]

Den sidste homogene komponent divideres væk, hvis transformationen er projective.

### Resize

Hvis et billede ændres fra `W0×H0` til `W1×H1` uden aspect-ratio preservation:

\[
u_1=u_0\frac{W_1}{W_0}
\]

\[
v_1=v_0\frac{H_1}{H_0}.
\]

Matrix:

\[
T_\text{resize}=
\begin{bmatrix}
W_1/W_0&0&0\\
0&H_1/H_0&0\\
0&0&1
\end{bmatrix}.
\]

Hvis MediaPipe analyserer det resized billede, men du tegner på originalen, anvender du den inverse skalering.

### Crop

For et crop med øverste venstre hjørne `(c_x,c_y)`:

\[
u_\text{crop}=u-c_x
\]

\[
v_\text{crop}=v-c_y.
\]

Inverse mapping:

\[
u=u_\text{crop}+c_x
\]

\[
v=v_\text{crop}+c_y.
\]

Det er den translation, der meget ofte glemmes, når overlays tilsyneladende har korrekt størrelse men konstant ligger forskudt.

### Letterbox / FIT

Hvis et `W_s×H_s` billede skaleres ind i `W_d×H_d` uden cropping:

\[
s=\min\left(\frac{W_d}{W_s},\frac{H_d}{H_s}\right)
\]

og med padding:

\[
d_x=\frac{W_d-sW_s}{2}
\]

\[
d_y=\frac{H_d-sH_s}{2},
\]

fås:

\[
u_d=su_s+d_x
\]

\[
v_d=sv_s+d_y.
\]

Inverse:

\[
u_s=\frac{u_d-d_x}{s},
\qquad
v_s=\frac{v_d-d_y}{s}.
\]

Hvis du glemmer `d_x/d_y`, bliver hele skelettet forskudt.

### Center crop / FILL

Ved “fill” er skalafaktoren i stedet:

\[
s=\max\left(\frac{W_d}{W_s},\frac{H_d}{H_s}\right).
\]

Her bliver overskydende billedareal skåret væk. Samme affine formel virker, men `d_x` eller `d_y` vil nu repræsentere crop-offset og kan geometrisk opfattes som negativ padding.

Det er en vigtig forklaring på, hvorfor et overlay, der ser perfekt ud oven på en `PreviewView`, ikke nødvendigvis kan gemmes direkte oven på videoens pixels.

### Mirroring

Ved horisontal spejling af continuous normalized coordinates:

\[
x'=1-x.
\]

For integer pixel indices:

\[
i'=W-1-i.
\]

Det er vigtigt at skelne mellem:

```text
kameraets faktiske pixelbuffer
```

og

```text
en selfie-lignende spejlet UI-preview.
```

Du skal transformere landmarks efter den **buffer, de faktisk blev beregnet fra**, ikke efter hvad brugeren subjektivt ser på skærmen.

### Rotation

For en fysisk 90° clockwise rotation af integer pixels fra et `W×H` billede:

\[
u'=H-1-v
\]

\[
v'=u,
\]

og outputstørrelsen bliver:

```text
W' = H
H' = W
```

Hvis du i stedet kun ændrer metadata, er pixels ikke blevet roteret. Det er en helt anden situation.

### MediaPipes interne resize og ROI skal normalt ikke “undoes” af dig

Dette er en meget vigtig detalje.

Pose-modellen arbejder internt på cropped/aligned tensors, men MediaPipe Tasks' graph fjerner selv letterboxing og transformerer de normaliserede landmarks tilbage fra pose-ROI'en til taskens inputbillede. Source graph'et viser eksplicit `LandmarkLetterboxRemovalCalculator` efterfulgt af `LandmarkProjectionCalculator`; sidstnævnte anvender ROI rotation, størrelse og center for at flytte `x/y` tilbage. citeturn20view5turn21view1turn21view2

Du skal derfor **ikke** forsøge at kompensere for den interne `256×256` BlazePose crop.

Du skal kun kompensere for de transformationer, **din egen kode udførte før oprettelsen af MediaPipe-inputbilledet**, og for transformationer efter inference frem til den gemte frame.

Det giver denne gode mentale model:

```text
original video frame
        │
        ├── dine transforms ──► exact MediaPipe input
        │                            │
        │                       MediaPipe intern ROI
        │                            │
        │                       MediaPipe undo'er ROI
        │                            │
        │                      normalized landmarks
        │
        ◄── inverse af dine transforms
        │
annotated saved frame
```

### Bitmap, ImageProxy og OpenCV Mat

Selve det, at data repræsenteres som `Bitmap`, `ImageProxy` eller `Mat`, er ikke det geometriske problem. Problemet er operationerne mellem dem.

Eksempelvis kan denne kæde være geometrisk identisk:

```text
ImageProxy YUV
→ RGB Bitmap
→ MediaPipe Image
```

hvis den kun ændrer farverepræsentation.

Men hvis koden samtidig gør:

```text
ImageProxy
→ rotate(rotationDegrees)
→ mirror()
→ centerCrop()
→ resize()
→ Bitmap
→ MediaPipe
```

er det det resulterende Bitmap, som MediaPipes normalized landmarks refererer til.

En robust implementation bør derfor aldrig have skjult “special logic” såsom:

```kotlin
if (portrait) swapXAndY(...)
```

spredt rundt i applikationen. Alle geometriske operationer bør samles i en kendt transform matrix.

Et simpelt design kunne være:

```kotlin
data class FrameGeometry(
    val analysisWidth: Int,
    val analysisHeight: Int,
    val savedWidth: Int,
    val savedHeight: Int,
    val analysisToSaved: Matrix
)

fun landmarkToSavedPixel(
    xNorm: Float,
    yNorm: Float,
    geometry: FrameGeometry
): PointF {
    val p = floatArrayOf(
        xNorm * geometry.analysisWidth,
        yNorm * geometry.analysisHeight
    )

    geometry.analysisToSaved.mapPoints(p)
    return PointF(p[0], p[1])
}
```

Det vigtigste er ikke den konkrete klasse, men kontrakten:

> Enhver 2D-coordinate i systemet skal have et eksplicit navn for det coordinate frame, den tilhører.

Navne som `wristX` er utilstrækkelige. Brug hellere begreber som:

```text
wristNormalizedAnalysis
wristPixelAnalysis
wristPixelSaved
wristPixelPreview
```

## CameraX, PreviewView, rotation, crop og gemte frames

CameraX er netop et sted, hvor koordinatfejl let opstår, fordi en CameraX use case består af både en pixelbuffer **og transformationsinformation**. Androids officielle dokumentation siger eksplicit, at transformationsinformationen beskriver, hvordan bufferen skal crops og roteres for brugerpræsentation. citeturn21view5

### ImageProxy-koordinater er ikke PreviewView-koordinater

Androids egen vejledning bruger dette som konkret eksempel: hvis en detector producerer coordinates i ImageAnalysis, skal de transformeres, før de tegnes i `PreviewView`. Deres eksempel bruger både:

```kotlin
imageProxy.cropRect
imageProxy.imageInfo.rotationDegrees
```

og bygger en matrix mellem analysebufferens crop rectangle og PreviewView. citeturn21view5

Det bekræfter direkte din antagelse:

> **PreviewView.width og PreviewView.height må ikke bruges direkte som MediaPipes image width/height.**

Eksempel:

```text
ImageAnalysis = 1280×720
PreviewView   = 1080×2400
```

så er dette forkert:

```kotlin
screenX = landmark.x() * previewView.width
screenY = landmark.y() * previewView.height
```

medmindre du tilfældigvis har bevist, at previewtransformationen er ren proportional skalering uden crop, rotation eller mirror.

Det er normalt ikke en sikker antagelse.

### CameraX ViewPort er nyttig, men løser ikke pixelmapping automatisk

Hvis `Preview`, `ImageAnalysis` og f.eks. `ImageCapture` placeres i samme `UseCaseGroup` med samme `ViewPort`, garanterer CameraX, at deres crop rects refererer til det samme område af kamerasensoren. De forskellige outputs kan stadig have forskellige opløsninger. Android anbefaler netop ViewPort for WYSIWYG-adfærd. citeturn14search1

Det er værdifuldt for din arkitektur:

```text
Preview
ImageAnalysis
ImageCapture
```

kan få samme FOV.

Men det betyder stadig ikke:

```text
same pixel coordinates
```

fordi 640×480 og 1920×1440 repræsenterer samme FOV med forskellige pixels.

### ImageCapture og EXIF

Android dokumenterer, at `ImageCapture` anvender crop rect før lagring, mens rotation kan gemmes i EXIF-metadata. citeturn21view5

Det giver en klassisk fejlkilde:

```text
JPEG pixel array = landscape
EXIF says        = rotate 90°
gallery display  = portrait
```

Hvis din Bitmap-decoder fysisk autoroterer billedet, mens dine landmarks stadig refererer til den u-roterede buffer, er coordinates forkerte.

Til analysebilleder anbefaler jeg en deterministisk regel:

> **Før rendering skal saved frame have én eksplicit fysisk pixelorientation.**

Med andre ord:

```text
decode
→ resolve orientation into pixels
→ know exact W×H
→ transform landmarks to that W×H
→ draw
→ save with neutral/unambiguous orientation
```

frem for at tegne på pixels, mens en separat EXIF-orientation stadig skal fortolkes bagefter.

### Sensorintrinsics er heller ikke direkte video-frame intrinsics

Android Camera2 kan på understøttede enheder returnere:

```text
LENS_INTRINSIC_CALIBRATION =
[fx, fy, cx, cy, skew]
```

og dokumenterer den til projektion af camera-centric 3D til sensorpixels. citeturn21view4

Men Android dokumenterer samtidig, at disse intrinsics er angivet i `preCorrectionActiveArraySize` coordinate system, ikke automatisk i din `720×1280` CameraX analysebuffer. De kan desuden være `null` på nogle devices. citeturn21view4

Det betyder, at du ikke bør gøre dette:

```text
get LENS_INTRINSIC_CALIBRATION
→ pass unchanged to solvePnP for 720×1280 frame
```

medmindre du har transformet intrinsics gennem sensor crop, eventuel geometric correction, scaling og orientation.

OpenCV gør samme princip eksplicit: hvis et billede skaleres, skal `fx`, `fy`, `cx`, `cy` skaleres tilsvarende. citeturn17view1

Android kan også eksponere `LENS_DISTORTION`; det er optional og beskriver radial/tangential geometrisk distortion. Android skelner desuden mellem pre-correction active array og den aktive array efter geometrisk distortion correction. citeturn18view1turn18view2

For en første produktionsversion vil en ofte mere kontrollerbar løsning være at kalibrere kameraet gennem **den samme capture pipeline og opløsning**, som din analyzer anvender, frem for at begynde med rå sensorintrinsics og forsøge at rekonstruere hele OEM/CameraX-croppipelinen.

## Fra et nyt MediaPipe-3D-punkt tilbage til billedet

Dette er kernen i problemet.

Antag:

\[
S=\text{shoulderWorld}
\]

\[
W=\text{wristWorld}
\]

og at du konstruerer et nyt punkt:

\[
T=S+\lambda(W-S).
\]

Eller måske:

\[
T=\text{projection of wrist onto target plane}.
\]

`T` befinder sig nu i **Pose World coordinate frame**.

Man kunne være fristet til at tænke:

```text
T.x → image x
T.y → image y
```

eller:

```text
normalize T somehow
```

men ingen af delene er korrekt.

### Hvorfor eksisterende WorldLandmark og ImageLandmark ikke har en simpel formel imellem sig

For hvert landmark har du to estimater:

```text
World:
(Xi, Yi, Zi)

Image:
(ui, vi)
```

men relationen er ikke:

\[
u_i=aX_i+b
\]

eller:

\[
u_i=X_i/W.
\]

Under et pinhole camera er relationen:

\[
\begin{bmatrix}
X_c\\Y_c\\Z_c
\end{bmatrix}
=
R
\begin{bmatrix}
X_w\\Y_w\\Z_w
\end{bmatrix}
+t
\]

derefter:

\[
u=f_x\frac{X_c}{Z_c}+c_x
\]

\[
v=f_y\frac{Y_c}{Z_c}+c_y
\]

før eventuel lens distortion. OpenCV dokumenterer netop denne model og `solvePnP` som beregningen af `R,t` fra 3D↔2D correspondences, når intrinsics er kendte. citeturn17view0turn17view1

MediaPipe giver dig:

```text
Xi Yi Zi
ui vi
```

men ikke den nødvendige `K,R,t`-pakke for Pose world landmarks.

### Sourcekoden lukker et vigtigt spørgsmål

Navnet `WorldLandmarkProjectionCalculator` kunne lyde som om MediaPipe internt faktisk har den 3D→2D-projektion, du mangler.

Det har den ikke.

For image landmarks udfører `LandmarkProjectionCalculator`:

1. flytning omkring ROI-center,
2. rotation,
3. skalering med ROI-bredde/højde,
4. translation til ROI-center i det fulde billede. citeturn21view1

Men WorldLandmarkProjectionCalculator gør kun:

```cpp
x' = cos(a)*x - sin(a)*y
y' = sin(a)*x + cos(a)*y
```

og efterlader resten af 3D-punktet i world-format. citeturn20view3

Pose-taskens aktuelle graph sender eksplicit world-landmarks gennem netop denne calculator. citeturn21view2

**Min tekniske konklusion ud fra sourcekoden er derfor:** der eksisterer ikke et skjult offentliggjort `MediaPipeWorldToPixel()`-trin, som du blot mangler at kalde. Du skal enten undgå behovet eller selv estimere projektionen.

### Den fysisk korrekte løsning: camera calibration + PnP

Du har allerede perfekte kandidater til 3D↔2D correspondences:

```text
PoseWorld[LEFT_SHOULDER]  ↔ PoseImage[LEFT_SHOULDER]
PoseWorld[RIGHT_SHOULDER] ↔ PoseImage[RIGHT_SHOULDER]
PoseWorld[LEFT_HIP]       ↔ PoseImage[LEFT_HIP]
...
```

Image-koordinaterne omregnes til analysis pixels:

\[
u_i=x_iW
\]

\[
v_i=y_iH.
\]

Hvis kameraets intrinsics `K` er kendte, kan du estimere:

\[
R,t
\]

for denne frame med Perspective-n-Point.

OpenCVs `solvePnP` er lavet præcis til:

```text
3D object points
+
corresponding 2D image points
+
camera matrix
+
distortion coefficients
→
rotation + translation
```

og finder en pose, der minimerer reprojection error. `solvePnPRansac` findes til tilfælde med outlier-correspondences. citeturn17view0

Derefter har du:

\[
X_c=RX_{MP}+t
\]

for ethvert nyt MediaPipe-world-point.

Og:

\[
\begin{aligned}
x'&=X_c/Z_c\\
y'&=Y_c/Z_c\\
u&=f_xx'+c_x\\
v&=f_yy'+c_y.
\end{aligned}
\]

OpenCVs `projectPoints()` kan derefter projektere både dine originale landmarks og alle nye konstruerede 3D-punkter ved samme `rvec`, `tvec`, camera matrix og distortion model. Den samme kameramodel understøtter radial og tangential distortion. citeturn17view1

Din pipeline bliver dermed:

```text
Pose WorldLandmarks
        │
        ├─────────────┐
        │             │
        │       3D karate geometry
        │             │
        │        newPointWorld
        │             │
        ▼             ▼
matched ImageLandmarks
        │
        ▼
camera calibration K
        │
        ▼
solvePnP / robust fit
        │
        ▼
R,t
        │
        ▼
projectPoints(newPointWorld)
        │
        ▼
analysis-frame pixel
        │
        ▼
Tanalysis→saved
        │
        ▼
saved-frame pixel
```

Det er den metodisk rigtige løsning.

### Hofte-origin er ikke et problem for PnP

Det gør ikke noget, at MediaPipe world coordinates har hofterne som origin.

PnP kræver ikke, at object coordinates allerede befinder sig ved kameraet. `t` repræsenterer netop transformationen fra object/world frame til camera frame. OpenCV beskriver `rvec/tvec` som den transformation, der flytter 3D-punkter fra object coordinate system ind i camera coordinate system. citeturn17view0

Så manglen på absolut hip-to-camera distance kan i princippet absorberes i `t`.

Det vanskelige er snarere, at **MediaPipes estimerede 3D-kropsform ikke er perfekt metrisk/perspektivkonsistent**, hvilket betyder, at der muligvis ikke eksisterer ét `R,t`, der reprojicerer alle 33 points perfekt. Det følger naturligt af, at world pose er en monocular modelestimation, og at BlazePose-modelkortet eksplicit ikke lover metric-accurate depth. citeturn20view7turn20view8

Derfor bør du betragte PnP-resultatet som en **best-fit camera registration**, ikke som ground-truth motion capture.

### Reprojection error skal være en first-class metric

Efter at du har fundet `R,t`, skal du straks reprojicere de MediaPipe-world-landmarks, du allerede kender:

\[
\hat p_i=\pi(K,R,t,P_i^{world})
\]

og sammenligne dem med deres faktiske MediaPipe image landmarks:

\[
e_i =
\left\|
\hat p_i-p_i^{MP-image}
\right\|.
\]

Beregn eksempelvis:

```text
median reprojection error
RMS reprojection error
per-landmark error
maximum error
```

i pixels og gerne også normaliseret med f.eks. personens torso- eller bounding-box-størrelse.

Hvis PnP siger:

```text
projected wrist = (812, 430)
MediaPipe wrist = (765, 451)
```

er der allerede ca. 51 pixels uoverensstemmelse, før du overhovedet projicerer dit ideal point.

Det er meget værdifuld information. Din UI kan så undlade at vise “præcise” virtuelle 3D-konstruktioner på frames, hvor registrationen er dårlig.

### Hvilke landmarks skal bruges til PnP?

Ikke alle 33 bør nødvendigvis vægtes ens.

Til en punch-analyse ville jeg prioritere synlige, forholdsvis stabile anatomiske landmarks omkring:

```text
left/right shoulder
left/right hip
elbows
wrists
knees
possibly ankles
```

og sortere punkter med dårlig visibility/presence fra.

Et robust estimatortrin er relevant, fordi MediaPipe selv giver visibility/presence-information, og OpenCV tilbyder RANSAC-baseret PnP til correspondences med outliers. citeturn19view0turn17view0

Undgå især en geometrisk dårlig konfiguration, hvor næsten alle 3D-punkter ligger på samme linje eller giver meget lidt depth variation; PnP bliver bedre konditioneret med rumligt fordelte correspondences. OpenCVs iterative løsning bruger eksempelvis mindst seks non-planar object points til sin DLT-initialisering. citeturn17view0

### Hvis intrinsics ikke er kendte

Du har fire realistiske muligheder, i faldende grad af fysisk stringens.

**Kalibrér telefonen.** Dette er den bedste løsning, hvis præcisionen er vigtig. OpenCVs calibration-funktioner estimerer intrinsic/extrinsic camera parameters fra flere views af et kendt calibration pattern. citeturn17view1

**Brug Androids camera intrinsics**, hvis enheden tilbyder dem, men transformer dem korrekt fra sensorens coordinate system til den konkrete video/analysebuffer. `LENS_INTRINSIC_CALIBRATION` er optional og angives i sensorens pre-correction coordinate system. citeturn21view4

**Fit kamera og focal length sammen.** Det kan være praktisk, men monocular camera geometry har ambiguities, og du bør ikke efterfølgende behandle det fundne focal length eller camera distance som fysisk ground truth.

**Fit en empirisk 3D→2D affine model.** Det kan være overraskende nyttigt til overlays:

\[
\begin{bmatrix}
u\\v
\end{bmatrix}
=
\begin{bmatrix}
a_1&a_2&a_3&a_4\\
b_1&b_2&b_3&b_4
\end{bmatrix}
\begin{bmatrix}
X\\Y\\Z\\1
\end{bmatrix}.
\]

Med mange landmark-correspondences kan koefficienterne findes via least squares/robust regression.

Det er ikke en fysisk perspektivkamera-model, men for en person, der fylder relativt lidt af billedets depth range, kan det fungere som rendering approximation. Det skal i så fald betegnes som sådan.

Jeg ville personligt foretrække:

```text
calibrated K + solvePnP
```

for din produktionsløsning og bruge affine fitting som diagnostisk baseline.

### Hvorfor “tag samme relative position i 2D” ikke generelt virker

Antag et 3D-midpoint:

\[
M=\frac{A+B}{2}.
\]

Det er fristende at tegne:

\[
m=\frac{a+b}{2}
\]

mellem de to observerede 2D-landmarks.

Under en **affine/orthographic projection** er dette korrekt.

Under perspective projection er det generelt **ikke** korrekt, fordi:

\[
u=f\frac{X}{Z}+c
\]

indeholder en division med dybden. Hvis `A` og `B` har forskellig `Z`, bevares deres Euclidean midpoint ikke som 2D-midpoint.

Det er meget relevant for karate. Et punkt “20 cm videre langs underarmen” kan være markant forskelligt i 2D afhængigt af, om armen peger:

```text
sideways across image
```

eller:

```text
almost directly toward camera.
```

Det er netop derfor, 3D→2D kræver camera projection.

## Geometrien til karateanalyse

Den mest robuste analyzer bør bevidst vælge mellem **3D biomekanik** og **2D visual geometry** for hver feature.

### Ledvinkler

For en 3D-albuevinkel med:

```text
S = shoulderWorld
E = elbowWorld
W = wristWorld
```

definér:

\[
a=S-E
\]

\[
b=W-E
\]

og:

\[
\theta
=
\arccos
\frac{a\cdot b}{\|a\|\|b\|}.
\]

Dette er en ægte 3D-vinkel i den estimerede Pose world-geometri og er normalt langt mere meningsfuld biomekanisk end den synlige 2D-vinkel, som ændrer sig med kameraets viewpoint. Pose WorldLandmarks er netop tiltænkt bl.a. 3D angle/distance measurements, med de tidligere nævnte modelbegrænsninger. citeturn20view8

Men når vinklen skal **tegnes**, findes der to forskellige designs:

```text
Numerisk 3D-vinkel:
"Elbow extension: 174°"

Visuel arc:
projekteret i billedplanet
```

En almindelig 2D-arc mellem de synlige skulder/elbow/wrist-pixels illustrerer billedvinklen, ikke nødvendigvis den 3D-vinkel, tallet viser.

Hvis du vil have en geometrisk korrekt visualisering af selve 3D-vinklen, skal du:

1. konstruere arc'en i det relevante 3D-plan,
2. sample flere points langs arc'en,
3. projicere hvert 3D-point gennem `K,R,t`,
4. tegne den resulterende 2D-polyline.

### Shoulder-to-wrist-line

Hvis formålet blot er at vise den observerede arm:

```text
PoseImage.shoulder
→ PoseImage.wrist
```

og tegn direkte mellem dem.

Der er ingen grund til at gå:

```text
image → world → camera → image
```

for et punkt, MediaPipe allerede har givet dig i image coordinates. Det vil kun tilføje reprojection error.

Hvis du derimod vil vise:

```text
actual shoulder→wrist
versus
ideal 3D shoulder→idealWrist
```

bør første linje bruge image landmarks, mens anden linje projekteres fra din 3D-model med det estimerede kamera.

### Punch trajectory

Her er det vigtigt at definere, hvad ordet betyder.

En **screen-space trajectory** er simpelthen håndleddets/knuckles `x,y` over frames:

\[
(u_t,v_t).
\]

Den er perfekt egnet til at blive tegnet oven på videoen.

En **3D punch trajectory** er:

\[
(X_t,Y_t,Z_t)
\]

fra world landmarks.

Hvis du vil tegne den 3D-trajectory tilbage på en bestemt video, bør hvert tidspunkt projiceres gennem det camera pose, som gælder den pågældende frame. Hvis kameraet står fysisk stille, kan `K` naturligvis være konstant; det estimerede `R,t` kan stadig variere lidt på grund af MediaPipes frame-to-frame modelstøj.

Vær opmærksom på, at WorldLandmarks er hoftecentrerede. Hvis karateudøveren flytter hele kroppen gennem rummet, kan en trajectory i hip-relative world coordinates fjerne noget af den globale translation, du måske egentlig ønsker at vise. Til teknikanalyse kan det være en fordel; til absolut movement-through-room er det en begrænsning.

### Forearm alignment og wrist alignment

For:

```text
elbow → wrist → knuckles
```

kan Pose WorldLandmarks give armens 3D-retning, mens Hand Landmarker kan give langt mere detaljeret håndgeometri.

Men Pose World og Hand World må ikke direkte blandes, fordi Pose bruger hofte-midpoint som origin, mens Hand World bruger håndens geometriske center. citeturn19view0turn21view0

En avanceret løsning er at registrere håndens world frame til Pose world frame med en rigid/similarity transformation:

\[
P_\text{pose}=sRP_\text{hand}+t.
\]

Det kræver fælles eller anatomisk tilsvarende landmarks og bør valideres via residualer.

Til rendering er det meget enklere at forbinde Pose og Hand gennem deres **fælles image coordinate frame**.

### Chin og target-height

Pose har ikke et egentligt “chin landmark”. Hvis Face Landmarker kører på samme frame, vil dens image landmarks ofte være en bedre kilde til et target omkring hage/ansigt end en artificiel Pose-3D-hage. Face landmarks er netop knyttet til ansigtsbilledet, mens Face Landmarkers 3D-repræsentation ikke er Pose world coordinates. citeturn20view10turn20view1

Her bør du også skelne:

```text
horizontal line on the image
```

fra:

```text
3D plane of constant anatomical/world height.
```

Hvis du blot vil vise “target height” gennem hagens pixel:

\[
y=y_\text{chin}
\]

er det en billedhorisontal reference og kan tegnes direkte.

Men et fysisk vandret 3D-plan er ikke nødvendigvis vandret i billedet, hvis telefonen er tilted/rolled. Skal det være fysisk korrekt, kræver det en definition af vertical direction i 3D samt kameratransformationen.

### Ideal target og ideal wrist

Her er PnP-tilgangen særlig relevant.

Antag:

\[
S=\text{shoulder}
\]

og en ønsket 3D-punch direction `d`.

Et idealiseret target:

\[
T=S+Ld
\]

kan beregnes i Pose world coordinates.

Derefter:

\[
T_c=RT+t
\]

og:

\[
(u_T,v_T)=\pi(K,T_c).
\]

Så har du ét billedpunkt, som faktisk er konsistent med den samme projektive model, der bruges til resten af kroppen.

For “ideal versus actual” kunne du tegne:

```text
actual wrist:
MediaPipe image landmark

ideal wrist:
projected world geometry

deviation:
pixel line actual → ideal

3D deviation:
||WactualWorld - WidealWorld||
```

og holde de to mål klart adskilt i datamodellen.

Det giver både en biomekanisk værdi og en forståelig grafisk visualisering.

### Linjer og distortion

I en ideel pinhole-model projiceres en 3D-linje til en 2D-linje. citeturn17view1

På et lens-distorted smartphone-image er situationen mere subtil. OpenCVs kameramodel omfatter radial og tangential distortion, og Android kan ligeledes beskrive geometrisk linsedistorsion gennem Camera2 metadata. citeturn17view1turn18view1

Hvis du virkelig kræver høj overlay-præcision nær billedkanterne, bør en lang “ideal 3D line” derfor ikke nødvendigvis tegnes ved kun at:

```text
project endpoint A
project endpoint B
draw straight Canvas line
```

på en distorted frame.

En mere stringent metode er:

```text
sample 20 points along 3D line
→ projectPoints with distortion
→ draw polyline
```

så distortion også påvirker mellempunkterne.

For korte kropssegmenter tæt på billedcentrum vil forskellen ofte være visuelt lille, men arkitektonisk er sampled projection den korrekte generalisering.

## Anbefalet produktionsarkitektur

Jeg ville bygge din karate-analyzer omkring tre eksplicit adskilte lag.

### Analyse-space

Dette er det præcise bitmap/frame, som MediaPipe modtog.

Gem mindst:

```text
frameTimestamp
analysisWidth
analysisHeight

poseImageLandmarks[]
poseWorldLandmarks[]

eventuelt:
handImageLandmarks[]
handWorldLandmarks[]
faceImageLandmarks[]
```

For hvert landmark bør type-systemet eller klassehierarkiet gøre det svært at blande coordinate systems ved en fejl.

Eksempelvis konceptuelt:

```kotlin
data class NormalizedImagePoint(
    val x: Double,
    val y: Double
)

data class PoseWorldPointMeters(
    val x: Double,
    val y: Double,
    val z: Double
)

data class AnalysisPixelPoint(
    val x: Double,
    val y: Double
)

data class SavedFramePixelPoint(
    val x: Double,
    val y: Double
)
```

Ikke én generisk:

```kotlin
Point3D(x, y, z)
```

til det hele.

Det vil forhindre meget alvorlige fejl, fordi:

```text
Pose normalized z
Pose world z
Hand world z
Face z
```

alle betyder noget forskelligt. citeturn19view0turn21view0turn20view10

### Geometry-space

Lav al biomekanisk matematik på Pose world coordinates:

```text
elbow extension
shoulder rotation proxies
forearm direction
deviation
3D target construction
reflection/projection
ideal wrist
3D punch direction
```

Men mærk resultater eksplicit som:

```text
PoseWorldPoint
PoseWorldVector
PoseWorldAngle
```

Et nyt punkt som:

```text
idealTargetWorld
```

har på dette tidspunkt **ingen pixelposition**.

Det er en vigtig egenskab, ikke et problem.

### Rendering-space

Rendererens input bør være færdige 2D-primitives i **saved-frame pixels**:

```text
Point
Line
Polyline
Arc
Text
Circle
Arrow
```

Renderer bør ikke vide noget om Pose Landmarker.

En separat projection/mapping-service producerer dem:

```text
observed Pose image point
→ normalized-to-analysis pixel
→ analysis-to-saved matrix
→ SavedFramePixelPoint
```

eller:

```text
calculated Pose world point
→ world-to-camera projection
→ AnalysisPixelPoint
→ analysis-to-saved matrix
→ SavedFramePixelPoint
```

Det giver en meget ren dataflow:

```text
                         ┌── 2D observed geometry ────────────┐
MediaPipe image points ─┤                                     │
                         │                                     ▼
video frame ─ MediaPipe ─┤                              saved-frame pixels
                         │                                     │
MediaPipe world points ──┤─ 3D biomechanical geometry          ▼
                         │          │                        renderer
                         │          ▼
                         │   camera projection K,R,t
                         │          │
                         └──────────┘
```

### Gem transformationsmetadata sammen med hvert frame-resultat

For hver analyseret frame ville jeg gemme noget i retning af:

```text
videoTimestamp
sourceFrameWidth
sourceFrameHeight

sourceOrientation

cropRect
mirrorApplied

analysisWidth
analysisHeight

sourceToAnalysisMatrix
analysisToSourceMatrix

savedWidth
savedHeight
analysisToSavedMatrix

cameraIntrinsicId/calibrationId

PnPRvec
PnPTvec
reprojectionMedianPx
reprojectionRmsPx
```

Så kan du rekonstruere præcis, hvordan enhver prik kom til sin plads.

Det gør fejl som “alle landmarks er 73 pixels til højre” debuggable i stedet for mystiske.

### Brug helst samme decoded frame til analyse og annotation

Der findes også en tidsmæssig fejlkilde, som ingen coordinate transform kan løse.

MediaPipe VIDEO mode kræver timestamps for decoded video frames. LIVE_STREAM er asynkron, og MediaPipe dokumenterer, at nye inputframes kan blive ignoreret, hvis tasken allerede er optaget. citeturn19view0

Derfor kan dette være farligt:

```text
CameraX ImageAnalysis frame at t₁
→ MediaPipe
→ maximum punch detected

VideoCapture
→ later extract "approximately same" frame at t₂
→ draw landmarks from t₁
```

På et hurtigt karatepunch kan få milliseconds give synlig forskel i håndposition.

For dit workflow:

```text
recorded video
→ find maximum punch extension
→ save exact frame
→ annotate
```

ville jeg derfor stærkt foretrække at post-processere den **gemte video** i MediaPipe `VIDEO` mode og bevare det præcise decoded frame/timestamp, som resultatet hører til. MediaPipes dokumentation definerer netop VIDEO mode omkring decoded video frames med timestamps. citeturn19view0

Så ved maksimum extension:

```text
Frame N pixels
Pose result for Frame N
geometry from Frame N
```

bliver holdt sammen.

## Validering og den konkrete strategi jeg ville vælge

Inden du bygger avancerede karate-overlays, ville jeg gøre coordinate systemet målbart gennem nogle simple tests.

### Test normalized → pixel først

Tag en frame uden nogen resize/crop efter MediaPipe.

Tegn alle 33 Pose image landmarks via:

\[
u=xW,\qquad v=yH.
\]

De skal ramme kroppen præcist på det **eksakte bitmap, MediaPipe modtog**. MediaPipes egen rendering utility følger denne mapping. citeturn20view2

Hvis de ikke gør, har du allerede et problem før 3D kommer ind i billedet.

### Test hver ekstern transformation separat

Lav et billede med tydelige markører nær:

```text
top-left
top-right
bottom-right
bottom-left
center
```

og test derefter separat:

```text
resize
crop
90° rotate
180° rotate
270° rotate
mirror
letterbox
center crop
```

Kontrollér både:

\[
T(p)
\]

og:

\[
T^{-1}(T(p))\approx p.
\]

På den måde kan du bevise dine transformationer uden at blande MediaPipe-modelusikkerhed ind.

### Test CameraX separat fra den gemte frame

Vis tre overlays samtidig under udvikling:

```text
analysebuffer
PreviewView
saved image
```

og projekter de samme landmarks ind i hver deres coordinate system.

Android dokumenterer direkte, at ImageAnalysis→Preview kræver crop/rotation transformation, og at PreviewView selv udfører displaytransformationer. citeturn21view5

Hvis alle tre visninger passer, har du løst din 2D-pipeline.

### Test dernæst world→image registration

Når du implementerer PnP:

```text
Pose world points
+ matching Pose image points
→ solvePnP
```

må første use case **ikke** være et kunstigt ideal wrist.

Projicér i stedet først alle kendte world landmarks tilbage:

```text
world left shoulder → projected image left shoulder
world left elbow    → projected image left elbow
...
```

og sammenlign med MediaPipes egne image landmarks.

OpenCVs PnP er defineret netop ved minimering af denne reprojection error. citeturn17view0

Visualisér eksempelvis:

```text
● MediaPipe image landmark
× reprojection from Pose World
─ error vector between them
```

Det billede bliver et meget stærkt diagnostics-tool.

Først når denne test er acceptabel, har du ret til at stole på:

```text
newPointWorld
→ projectedPoint
```

### En god feature-policy for din karate-app

Jeg ville bruge følgende princip:

| Feature | Beregn i | Tegn via |
|---|---|---|
| Observeret skulder | Image landmark | direkte normalized→pixels |
| Observeret håndled | Image landmark | direkte normalized→pixels |
| Observeret skeleton | Image landmarks | direkte |
| 3D elbow angle | Pose world | talværdi; image points til simpel arc |
| Korrekt projekteret 3D-angle arc | Pose world | sample 3D arc + camera projection |
| Shoulder→wrist 3D-retning | Pose world | biomekanisk beregning |
| Synlig shoulder→wrist-linje | Image | direkte endpoints |
| Ideal wrist | Pose world | `K,R,t` → image |
| Ideal target | Pose world | `K,R,t` → image |
| Chin til UI | Face/Pose image | direkte image mapping |
| 3D chin | registreret 3D-model | camera projection |
| Punch trajectory i video | Image landmarks over tid | direkte pixel trajectory |
| 3D punch trajectory | World landmarks over tid | camera projection |
| Target-height på skærmen | Image space | `y = targetPixelY` |
| Fysisk 3D target-plane | World space | project plane/lines |
| Pixel deviation | Image pixels | 2D Euclidean distance |
| Biomekanisk deviation | Pose world | 3D Euclidean/vector metric |
| Fine hand alignment | Hand model | helst lokal 3D + image rendering |

### Den vigtigste implementeringsregel

I hele systemet bør denne operation være forbudt:

```text
PoseWorldPoint
→ multiply by image width/height
→ pixel
```

og tilsvarende bør dette være unødvendigt:

```text
PoseImageLandmark
→ PoseWorld
→ camera projection
→ pixel
```

når MediaPipe allerede har leveret den korrekte observerede `x/y`.

Den anbefalede todeling er:

\[
\boxed{
\text{MediaPipe image landmarks}
\rightarrow
\text{pixel-exact rendering}
}
\]

og

\[
\boxed{
\text{MediaPipe world landmarks}
\rightarrow
\text{3D karate geometry}
}
\]

med et eksplicit tredje trin kun hvor nødvendigt:

\[
\boxed{
P_{world}^{new}
\xrightarrow{R,t}
P_{camera}
\xrightarrow{K+\text{distortion}}
p_{analysis}
\xrightarrow{T_{analysis\to saved}}
p_{saved}
}
\]

Det er den arkitektur, der både respekterer, hvad MediaPipe faktisk returnerer, hvad sourcekoden faktisk gør, og hvordan et fysisk kamera projicerer 3D til pixels. MediaPipes image-landmark pipeline udfører selv intern ROI/letterbox-remapping, mens world-landmark pipeline ikke udfører nogen fuld perspective image projection; OpenCVs pinhole/PnP-model leverer netop det manglende matematiske led. citeturn21view1turn20view3turn21view2turn17view0turn17view1

For netop en smartphone-baseret karate analyzer ville jeg derfor gøre **frame-accurate MediaPipe image coordinates til den autoritative rendering truth**, **Pose WorldLandmarks til den autoritative 3D analysis space**, og behandle enhver projektion af et selvberegnet world point som en særskilt, valideret camera-registration-operation med en målbar reprojection error.
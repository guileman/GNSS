# TopoGNSS — Relevamiento y replanteo con Emlid Reach RS2

Aplicación Android (Kotlin + Jetpack Compose + Room, arquitectura MVVM) para topografía con receptores GNSS RTK que emiten NMEA, pensada y probada en diseño para **Emlid Reach RS2 / RS2+ / RS3**. Funciona 100 % offline.

## Funciones

| Módulo | Qué hace |
|---|---|
| **GNSS** | Conexión por Bluetooth SPP (RFCOMM), TCP cliente, TCP servidor o UDP. Reconexión automática, servicio en primer plano, consola NMEA y contadores de tramas. |
| **Barra de estado** | Persistente en todas las pantallas: solución (verde FIX, amarillo FLOAT, naranja DGPS, rojo SINGLE/sin datos), satélites, HRMS, VRMS, PDOP, edad de corrección y batería (si el receptor la informa). |
| **Relevar** | Promedio de 1/5/10/30 épocas con filtros (exigir FIX, HRMS/VRMS máximos), nombre autoincremental (P001, P002…), código/descripción, altura de antena, lista en vivo. Guarda σ combinando GST y dispersión real. |
| **Puntos** | Lista por tipo (relevado, COGO, a replantear, replanteado), edición, alta manual por coordenadas, exportación CSV. |
| **Proyectos — nomenclatura catastral** | Datos catastrales del predio (circunscripción, sección, manzana/chacra, parcela, partida) guardados en el proyecto; se muestran en la lista de proyectos y se incluyen como encabezado en la exportación CSV de puntos. |
| **Cálculo – Medición** | Distancia horizontal, desnivel, distancia inclinada, pendiente, azimut (DMS), ángulos y deflexiones en vértices, superficie (Gauss) y perímetro. Informa el factor de escala local. |
| **Cálculo – Crear puntos** | Punto medio, prolongación sobre alineación (con o sin extrapolar cota) y estación/desplazamiento perpendicular izquierda/derecha. |
| **Replanteo** | Distancia, "Avanzar/Retroceder" y "Derecha/Izquierda", corte/relleno, flecha que rota según el teléfono y radar de precisión a menos de 3 m. Brújula (vector de rotación + declinación WMM) fusionada con el rumbo GNSS al caminar. Alerta sonora y vibración dentro de la tolerancia (2/5/10 cm). |

## Mapa base y tema

- **Capas** (botón flotante arriba a la derecha del mapa): Calles (OpenStreetMap), Satélite (Esri World Imagery), Híbrido (satélite + rutas y nombres), Topográfico (OpenTopoMap) o Sin mapa. La elección se recuerda.
- El mapa aparece en **Relevar** (puntos y posición con círculo de precisión HRMS a escala), **Puntos** (botón "Ver mapa", tocar un punto abre su ficha), **Cálculo** (selección de puntos tocándolos en el mapa, con la poligonal dibujada) y **Replanteo** (modo "Mapa": sigue la posición, gira con el rumbo y muestra la línea al objetivo).
- **Sin conexión**: las teselas vistas se guardan en caché (hasta 600 MB). Antes de ir al campo, recorré la zona de trabajo en el mapa con Internet y la tendrás disponible offline.
- No requiere clave de API. Las imágenes satelitales son de Esri y su uso está sujeto a sus términos (atribución visible en el mapa).
- **Tema**: menú ⋮ de la barra superior → Según el sistema / Claro / Oscuro. En oscuro, la capa de calles se muestra invertida para uso nocturno.

## Sistemas de coordenadas

Cada proyecto define su plano de proyección. La proyección Transversa de Mercator usa las series de Krüger de orden 6 y está verificada contra PROJ (diferencias < 0,1 mm):

- **UTM WGS84** (zona 1-60, N/S).
- **POSGAR 2007 / Gauss-Krüger Argentina**, fajas 1-7 (EPSG 5343-5349).
- **TM local** con k0 = 1 centrada en la obra: las distancias planas coinciden con las de terreno a nivel milimétrico.

> El marco de las coordenadas es el de la base RTK. Para obtener POSGAR 2007 real la base debe estar en POSGAR (por ejemplo, corrección NTRIP de RAMSAC). Con base en WGS84/ITRF actual hay ~0,6 m de diferencia por movimiento de placa.

## Configuración del Emlid RS2 (Emlid Flow)

1. **Settings → Position output → Output 1**.
2. Elegí el canal:
   - **Bluetooth**: emparejá antes el teléfono desde los ajustes de Android.
   - **TCP** (rol servidor): con el hotspot del receptor la IP es `192.168.42.1`; usá el puerto que configures en Flow.
   - **TCP cliente / UDP**: cargá en Flow la IP del teléfono (la app la muestra) y el puerto.
3. Formato **NMEA**, con al menos **GGA, GST, GSA y RMC**. Sin GST la app no puede mostrar HRMS/VRMS.
4. La batería del RS2 no viaja por NMEA estándar, por eso la barra muestra "N/D". El parser deja un punto de extensión para tramas propietarias (`$P…BAT…`).

**Altura de antena**: si no la cargás en el receptor, ingresala en Relevar → Filtros (jalón + offset ARP→centro de fase según la ficha técnica de tu equipo). Se resta de la cota de cada punto y del replanteo.

## Estructura

```
app/src/main/java/com/topognss/app/
├── TopoApp.kt                 Contenedor de dependencias (Room, prefs, GNSS, brújula)
├── MainActivity.kt            Navegación, barra de estado, permisos
├── gnss/
│   ├── NmeaParser.kt          Parser NMEA con checksum (GGA, GST, GSA, RMC, VTG)
│   ├── GnssState.kt           Estado, épocas y agregador de sentencias
│   ├── Transports.kt          Bluetooth SPP, TCP cliente/servidor, UDP
│   ├── GnssManager.kt         Conexión única, reconexión, StateFlow/SharedFlow
│   └── GnssService.kt         Servicio en primer plano
├── geo/                       Transversa de Mercator, CRS, geodesia local
├── cogo/                      Cálculos COGO puros
├── sensors/CompassSensor.kt   Brújula y fusión de rumbo
├── ui/map/                    Mapa osmdroid: capas, overlay topográfico, caché offline
├── data/                      Room (proyectos y puntos), repositorio, CSV, preferencias
└── ui/                        Pantallas Compose + ViewModels
app/src/test/                  Tests JVM de geodesia, NMEA y COGO
.github/workflows/android.yml  CI: tests + APK release
```

## Compilar

- **GitHub Actions**: cada push a `main` corre los tests y genera el APK. Descargalo desde la pestaña *Actions → ejecución → Artifacts → TopoGNSS-apk*. Si empujás un tag `v1.0.0`, además se publica como Release.
- **Local**: `./gradlew assembleRelease` (JDK 17, Android SDK 35).

### Firma del APK

Sin configuración, el APK de release se firma con una clave debug generada en cada ejecución. Es instalable, pero cada nueva versión requiere desinstalar la anterior. Para actualizaciones sin desinstalar, creá un keystore y cargá estos secretos en *Settings → Secrets and variables → Actions*:

| Secreto | Valor |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `SIGNING_STORE_PASSWORD` | contraseña del keystore |
| `SIGNING_KEY_ALIAS` | alias de la clave |
| `SIGNING_KEY_PASSWORD` | contraseña de la clave |

```bash
keytool -genkeypair -v -keystore release.jks -alias topognss -keyalg RSA -keysize 2048 -validity 10000
```

## Notas técnicas

- Al conectarse al hotspot del RS2 (sin Internet), Android suele enrutar el tráfico por datos móviles; los sockets TCP/UDP se atan explícitamente a la red Wi-Fi.
- El replanteo calcula distancia y azimut verdadero en un plano tangente local (ENU), así la flecha se compara directamente con la brújula corregida por declinación. Los cálculos COGO usan el plano del proyecto.
- La brújula del teléfono se ve afectada por jalones metálicos, vehículos y estructuras. Caminando a más de 0,6 m/s el modo **Auto** usa el rumbo GNSS.

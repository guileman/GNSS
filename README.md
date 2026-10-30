# GNSS — Relevamiento y replanteo con Emlid Reach RS2

Aplicación Android (Kotlin + Jetpack Compose + Room, arquitectura MVVM) para topografía con receptores GNSS RTK que emiten NMEA, pensada y probada en diseño para **Emlid Reach RS2 / RS2+ / RS3**. Funciona 100 % offline.

📖 **[Guía de uso de todas las funciones, con diagramas](docs/ayuda/README.md)**

## Funciones

| Módulo | Qué hace |
|---|---|
| **GNSS** | Conexión por Bluetooth SPP (RFCOMM), TCP cliente, TCP servidor o UDP. Reconexión automática, servicio en primer plano, consola NMEA y contadores de tramas. |
| **Barra de estado** | Persistente en todas las pantallas: solución (verde FIX, amarillo FLOAT, naranja DGPS, rojo SINGLE/sin datos), satélites, HRMS, VRMS, PDOP, edad de corrección y batería (si el receptor la informa). |
| **Relevar** | Promedio de 1/5/10/30 épocas con filtros (exigir FIX, HRMS/VRMS máximos), nombre autoincremental (P001, P002…), código/descripción, altura de antena, lista en vivo. Guarda σ combinando GST y dispersión real. |
| **Puntos** | Lista por tipo (relevado, COGO, a replantear, replanteado), edición, alta manual por coordenadas, exportación CSV, **reporte en PDF** (bloques de medición agregados desde Cálculo y tabla de puntos replanteados con sus residuos dN/dE/distancia/corte-relleno, paginado automáticamente) e **importación de DXF/KML/KMZ** (botón "Importar"): cada vértice de puntos, líneas y polilíneas del archivo se carga como un punto individual con prefijo "IMP" (planas si es DXF, geográficas si es KML/KMZ). Cada punto puede tener una foto georreferenciada (se toma con la cámara del teléfono y queda asociada al punto). |
| **Proyectos — nomenclatura catastral** | Datos catastrales del predio (circunscripción, sección, manzana/chacra, parcela, partida) guardados en el proyecto; se muestran en la lista de proyectos y se incluyen como encabezado en la exportación CSV de puntos. |
| **Cálculo – Medición** | Distancia horizontal, desnivel, distancia inclinada, pendiente, azimut (DMS), ángulos y deflexiones en vértices, superficie (Gauss) y perímetro. Con el polígono cerrado, permite elegir un segundo polígono (p. ej. lo edificado) y descontarlo, mostrando una planilla de superficie (bruta, edificada, neta). Informa el factor de escala local. **Agregar al reporte**: cada selección de puntos se guarda como un bloque con nombre y descripción (coordenadas, tramos, azimutes, ángulos y, si es cerrado, superficie y perímetro) que se imprime en el reporte PDF. |
| **Cálculo – Crear puntos** | Punto medio, prolongación sobre alineación (con o sin extrapolar cota), estación/desplazamiento perpendicular izquierda/derecha, intersección directa por azimuts (dos visuales que se cruzan) e intersección por distancias (trilateración, con elección de lado). |
| **Cálculo – Resección** | "Estación libre" por el método de Tienstra: con 3 puntos conocidos y los 2 ángulos horizontales medidos entre ellos desde la estación (sin necesidad de medir distancias), calcula la posición de la estación. |
| **Cálculo – Curva** | Curva horizontal circular simple a partir de PC, tangente de entrada (automática o manual), radio y ángulo central. Calcula tangente, desarrollo, externa, ordenada media, cuerda larga y PT, y genera el estacado (cada N metros de arco, con cuerda y deflexión desde PC) listo para crear como puntos a replantear. |
| **Cálculo – Poligonal** | Poligonal observada (azimut + distancia por tramo, p. ej. de estación total), partiendo de un punto conocido y cerrando en otro (o en el mismo, poligonal cerrada). Calcula el error de cierre (ΔE, ΔN, cierre lineal y precisión relativa 1:N) y distribuye la corrección proporcionalmente a la distancia recorrida (regla de Bowditch/compás), generando los vértices ajustados como puntos COGO. |
| **Replanteo** | Modo **Punto**: distancia, "Avanzar/Retroceder" y "Derecha/Izquierda", corte/relleno, flecha que rota según el teléfono y radar de precisión a menos de 3 m. Brújula (vector de rotación + declinación WMM) fusionada con el rumbo GNSS al caminar. Alerta sonora y vibración dentro de la tolerancia (2/5/10 cm). Modo **Eje**: replanteo continuo de alineaciones (p. ej. una cañería o un cordón) — se elige una polilínea de puntos y la app muestra en todo momento la estación acumulada y el desvío lateral (derecha/izquierda) respecto del eje mientras se camina. |
| **Plomada virtual** | Nivel de burbuja (menú ⋮) que usa el acelerómetro del propio teléfono — no el del receptor RS2, que no expone su IMU por ningún canal documentado — para indicar cuán a plomo está el jalón: ángulo total de inclinación, desvío equivalente en mm por metro de jalón y color de aviso (verde dentro de 0,3°). |

## Mapa base y tema

- **Capas** (botón flotante arriba a la derecha del mapa): Calles (OpenStreetMap), Satélite (Esri World Imagery), Híbrido (satélite + rutas y nombres), Topográfico (OpenTopoMap) o Sin mapa. La elección se recuerda.
- El mapa aparece en **Relevar** (puntos y posición con círculo de precisión HRMS a escala), **Puntos** (botón "Ver mapa", tocar un punto abre su ficha), **Cálculo** (selección de puntos tocándolos en el mapa, con la poligonal dibujada) y **Replanteo** (modo "Mapa": sigue la posición, gira con el rumbo y muestra la línea al objetivo).
- **Sin conexión**: las teselas vistas se guardan en caché (hasta 600 MB). Antes de ir al campo, recorré la zona de trabajo en el mapa con Internet y la tendrás disponible offline.
- No requiere clave de API. Las imágenes satelitales son de Esri y su uso está sujeto a sus términos (atribución visible en el mapa).
- **Tema**: menú ⋮ de la barra superior → Según el sistema / Claro / Oscuro. En oscuro, la capa de calles se muestra invertida para uso nocturno.

### Google Maps (opcional)

Con una clave de **Maps SDK for Android** el mapa usa Google Maps (calles, satélite, híbrido, relieve); sin clave, la app sigue con OpenStreetMap/Esri. Se elige en **Ajustes → Mapa**.

1. En Google Cloud Console: crear un proyecto, habilitar **Maps SDK for Android** y crear una **clave de API** (restringida a esa API).
2. En GitHub: Settings → Secrets and variables → Actions → nuevo secreto `MAPS_API_KEY` con la clave. En una compilación local: `MAPS_API_KEY=...` en `local.properties`.
3. Si se restringe la clave por app Android, usar el paquete `com.topognss.app` y el SHA-1 del keystore de release (sin keystore propio, cada compilación de CI usa una clave debug distinta).

## Importar y exportar puntos

En **Puntos → ⋮**:
- **Importar** CSV/TXT (separador y encabezado detectados; sin encabezado se elige el orden P,E,N,Z,D, P,N,E,Z,D, etc.; planas o geográficas), DXF y KML/KMZ.
- **Exportar DXF** (R12): POINT 3D por capa de tipo (PUNTOS_RELEVADO, PUNTOS_COGO…) y etiquetas TEXT en las capas NOMBRE, DESCRIPCION y TIPO.
- **Exportar CSV** y **reporte en PDF**.

## Sistemas de coordenadas

Cada proyecto define su plano de proyección. La proyección Transversa de Mercator usa las series de Krüger de orden 6 y está verificada contra PROJ (diferencias < 0,1 mm):

- **UTM WGS84** (zona 1-60, N/S).
- **POSGAR 2007 / Gauss-Krüger Argentina**, fajas 1-7 (EPSG 5343-5349).
- **TM local** con k0 = 1 centrada en la obra: las distancias planas coinciden con las de terreno a nivel milimétrico.

> El marco de las coordenadas es el de la base RTK. Para obtener POSGAR 2007 real la base debe estar en POSGAR (por ejemplo, corrección NTRIP de RAMSAC). Con base en WGS84/ITRF actual hay ~0,6 m de diferencia por movimiento de placa.

## Configuración del Emlid RS2 (Emlid Flow)

1. **Settings → Position output → Output 1**.
2. Elegí el canal:
   - **Bluetooth**: en la app, Receptor → Buscar dispositivos → tocá el receptor para emparejarlo; conecta solo. Los ya emparejados conectan con un toque.
   - **Wi-Fi** (TCP, rol servidor, puerto `9001`): conectá el teléfono a la red Wi-Fi del receptor desde los ajustes de Android, volvé a la app y tocá Conectar (IP fija `192.168.42.1`).
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

- **GitHub Actions**: cada push a `main` corre los tests y genera el APK. Descargalo desde la pestaña *Actions → ejecución → Artifacts → GNSS-apk*. Si empujás un tag `v1.0.0`, además se publica como Release.
- **Local**: `./gradlew assembleRelease` (JDK 17, Android SDK 35).

### Página de descarga

`site/index.html` es una página estática (publicada con GitHub Pages) que muestra siempre el último Release: botón de descarga del APK, código QR, notas de versión y pasos de instalación. Cada push a `main` compila, publica el Release `v1.0.<n>` y la página lo toma sola.

Activación (una sola vez): *Settings → Pages → Build and deployment → Source: GitHub Actions*. La dirección queda en `https://guileman.github.io/GNSS/`.

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

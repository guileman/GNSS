# Ayuda — TopoGNSS

Guía de uso de todas las funciones de la app, con diagramas explicativos en vez de capturas de
pantalla reales (la numeración y el aspecto exacto de cada pantalla puede cambiar; los diagramas
describen el concepto, no un layout fijo).

## Índice

- [GNSS: conexión al receptor](#gnss-conexión-al-receptor)
- [Relevar](#relevar)
- [Puntos](#puntos)
  - [Fotografías georreferenciadas](#fotografías-georreferenciadas)
  - [Exportar / importar](#exportar--importar)
  - [Reporte de replanteo en PDF](#reporte-de-replanteo-en-pdf)
- [Cálculo (COGO)](#cálculo-cogo)
  - [Medición](#medición)
  - [Crear puntos](#crear-puntos)
  - [Poligonal](#poligonal)
  - [Intersección y resección](#intersección-y-resección)
  - [Curvas horizontales](#curvas-horizontales)
  - [Superficie con descuento](#superficie-con-descuento)
- [Replanteo](#replanteo)
  - [Modo Punto](#modo-punto)
  - [Modo Eje](#modo-eje)
- [Plomada virtual](#plomada-virtual)
- [Proyectos](#proyectos)
  - [Sistemas de coordenadas](#sistemas-de-coordenadas)
  - [Nomenclatura catastral](#nomenclatura-catastral)
- [Mapa y tema](#mapa-y-tema)

---

## GNSS: conexión al receptor

Pantalla **GNSS** (pestaña inferior). Conectá el Emlid Reach RS2 por Bluetooth SPP, TCP (cliente o
servidor) o UDP. La app reintenta la conexión sola si se corta y mantiene un servicio en primer
plano para no perderla con la pantalla apagada. La consola NMEA de abajo sirve para confirmar que
llegan tramas GGA/GST/GSA/RMC — sin GST no hay HRMS/VRMS.

La barra de estado (visible en todas las pantallas) resume: solución por color (verde FIX,
amarillo FLOAT, naranja DGPS, rojo SINGLE/sin datos), satélites, HRMS, VRMS, PDOP y edad de la
corrección.

## Relevar

Promedia 1/5/10/30 épocas con filtros opcionales (exigir FIX, HRMS/VRMS máximos). El nombre del
punto se autoincrementa con el prefijo configurado (P001, P002…); podés agregar código/descripción
y la altura de antena. La desviación guardada combina el GST del receptor con la dispersión real
de las épocas promediadas.

## Puntos

Lista de todos los puntos del proyecto activo, filtrable por tipo (relevado, COGO, a replantear,
replanteado). Tocar un punto abre su ficha para editar nombre/código, ver todos sus datos y
eliminarlo.

### Fotografías georreferenciadas

Desde la ficha de un punto, el botón de cámara saca una foto con la app de cámara del teléfono
(sin pedir el permiso de cámara en la app: se delega a la app externa) y la asocia a ese punto.
Se puede reemplazar o quitar la foto en cualquier momento.

### Exportar / importar

- **CSV**: exporta todos los puntos visibles (según el filtro activo) con sus coordenadas,
  solución y metadatos, listo para abrir en una planilla.
- **Importar**: carga puntos desde un archivo **DXF** o **KML/KMZ** elegido con el selector de
  documentos del sistema. Cada vértice del archivo —de un punto, una línea o una polilínea— se
  importa como un punto individual con prefijo **IMP**; no se conserva la geometría de la
  polilínea original, solo sus vértices sueltos. Los puntos de un DXF se cargan en las planas del
  proyecto; los de un KML/KMZ, en geográficas WGS84.

### Reporte de replanteo en PDF

El botón **PDF** genera y comparte (por cualquier app instalada: Drive, WhatsApp, correo…) una
tabla con todos los puntos "Replanteado" del proyecto y sus residuos respecto del objetivo
(dN, dE, distancia, corte/relleno), paginada automáticamente si hay muchos.

## Cálculo (COGO)

### Medición

Elegís 2 o más puntos (de la lista o tocándolos en el mapa) y la app calcula distancia horizontal,
desnivel, distancia inclinada, pendiente, azimut (en grados decimales y DMS), ángulos y
deflexiones en cada vértice intermedio, y —con 3 o más puntos cerrando un polígono— superficie
(fórmula de Gauss) y perímetro. Siempre informa el factor de escala local del sistema de
coordenadas elegido.

### Crear puntos

A partir de puntos existentes: punto medio entre dos, prolongación sobre una alineación (con o
sin extrapolar la cota) y estación con desplazamiento perpendicular (derecha/izquierda) respecto
de una alineación de dos puntos.

### Poligonal

![Poligonal: ajuste por compás](img/poligonal.svg)

Pestaña **Poligonal** dentro de Cálculo. Se ingresa el punto de partida y, tramo por tramo, el
azimut y la distancia observados (los mismos datos que se anotan en una libreta de poligonal
clásica). Si se conoce la posición real del punto de cierre, la app calcula el **error de cierre**
(componentes Este/Norte, error lineal y precisión 1:N) y distribuye esa corrección entre todos los
vértices proporcionalmente a la distancia acumulada desde el inicio — el método de **compás o
Bowditch**, el más usado en poligonales topográficas de precisión media. Los vértices ajustados se
pueden guardar como puntos COGO del proyecto.

### Intersección y resección

![Intersección y resección](img/interseccion-resection.svg)

- **Intersección por azimutes**: dos puntos conocidos y el azimut observado desde cada uno hacia
  el punto buscado; la app calcula dónde se cruzan esas dos visuales.
- **Intersección por distancias**: dos puntos conocidos y la distancia medida desde cada uno al
  punto buscado (con una cinta o una visual estadimétrica, por ejemplo); hay dos soluciones
  posibles (a la derecha o a la izquierda de la línea entre los dos puntos conocidos) y se elige
  cuál usar.
- **Resección (problema de Pothenot, método de Tienstra)**: parado en un punto cuya posición no
  se conoce, se observan los ángulos hacia 3 puntos conocidos (dados en orden horario como se ven
  desde la estación) y la app calcula la posición de la estación. Útil para instalarse en un punto
  cualquiera sin necesidad de GNSS en ese instante, siempre que haya 3 referencias visibles.

### Curvas horizontales

![Curva horizontal](img/curva-horizontal.svg)

A partir de dos alineaciones (o una azimut de entrada manual), un radio y un ángulo de deflexión
(Δ), la app calcula los elementos clásicos de una curva circular — tangente (T), longitud de arco
(L), externa (E), ordenada media (M) y cuerda larga — y genera el **estacado**: una lista de puntos
sobre la curva cada intervalo elegido, desde el PC (principio de curva) hasta el PT (fin de
curva), lista para guardar y usar en el replanteo.

### Superficie con descuento

![Superficie con descuento de edificado](img/superficie-descuento.svg)

Dentro de los resultados de Medición: además del polígono principal (el lote), se puede elegir un
segundo polígono interior (lo construido) y la app descuenta su área del total, mostrando una
pequeña **planilla de superficie** con el área bruta, el área descontada y la neta.

## Replanteo

### Modo Punto

Elegís un punto "a replantear" como objetivo y la app muestra la distancia que falta, si hay que
avanzar/retroceder y a la derecha/izquierda, el corte o relleno en cota, y una flecha que rota
según la orientación del teléfono (brújula por vector de rotación + declinación magnética WMM,
fusionada con el rumbo del GNSS al caminar). Dentro de la tolerancia elegida (2/5/10 cm) avisa con
sonido y vibración.

### Modo Eje

![Replanteo de eje](img/replanteo-eje.svg)

Para replantear una alineación completa (una cañería, un cordón, un eje de camino) en vez de un
punto suelto: se elige una polilínea de puntos (el eje) y la app muestra en todo momento la
**estación** (distancia acumulada desde el inicio del eje hasta el pie de la perpendicular más
cercana a la posición actual) y el **desvío lateral** (derecha/izquierda) respecto de ese eje,
para ir corrigiendo la trayectoria mientras se camina sobre él.

## Plomada virtual

![Plomada virtual](img/plomada-virtual.svg)

Accesible desde el menú **⋮** de la barra superior. Apoyando el teléfono contra un jalón o baliza,
en vertical, usa el acelerómetro **del propio teléfono** (el receptor RS2 no expone su sensor
inercial por ningún canal documentado) para mostrar un nivel de burbuja: el ángulo total de
inclinación respecto de la vertical, su equivalente en milímetros por cada metro de longitud del
jalón, y un color de aviso (verde dentro de 0,3° ≈ 5 mm/m).

## Proyectos

### Sistemas de coordenadas

Cada proyecto define su propio sistema: UTM WGS84, POSGAR 2007/Gauss-Krüger (fajas 1-7) o un plano
Transversa de Mercator local centrado en la obra (k0 = 1, distancias planas ≈ distancias de
terreno). Cambiar el sistema recalcula las planas de todos los puntos existentes.

### Nomenclatura catastral

En el formulario de Proyectos se pueden completar los datos catastrales del predio
(circunscripción, sección, manzana/chacra, parcela y partida inmobiliaria) — todos opcionales,
porque el formato catastral varía mucho según la provincia o el país. Cuando hay al menos un
campo completo, se muestra armado en la tarjeta del proyecto y se incluye como encabezado en la
exportación CSV de puntos.

## Mapa y tema

El mapa (osmdroid) aparece en Relevar, Puntos, Cálculo y Replanteo, con capas intercambiables
(Calles, Satélite, Híbrido, Topográfico o sin mapa) y caché offline de las teselas ya vistas. El
tema de la interfaz (según el sistema, claro u oscuro) se elige en el menú ⋮ de la barra superior.

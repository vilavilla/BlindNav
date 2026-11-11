# Guía de prueba de BlindNav

## Antes de empezar

- Instala `app/build/outputs/apk/debug/app-debug.apk` en un móvil con Android 8 o posterior.
- Haz las primeras pruebas en una zona peatonal controlada y con una persona vidente acompañando.
- No uses la aplicación como autorización para entrar en la calzada. La cámara y los datos del mapa pueden fallar o estar incompletos.
- Activa ubicación precisa, datos móviles y volumen multimedia.

## 1. Protección con cámara

1. Abre BlindNav y pulsa **PROTECCIÓN CON CÁMARA**.
2. Comprueba que la cámara permanece encendida.
3. Acércate lentamente a una silla, caja u otro objeto inmóvil, sin riesgo.
4. Comprueba que aparecen avisos de precaución/peligro y que el sonar acelera al acercarte.
5. Pulsa **¿QUÉ HAY DELANTE?**. Debe indicar el objeto si el modelo lo reconoce; si no, debe decir “objeto no identificado”, su posición y proximidad.
6. Quita el permiso de cámara durante la prueba. La aplicación debe indicar que la detección no está disponible; nunca debe mostrar ese fallo como “seguro”.

## 2. Ruta peatonal nueva

1. En la pantalla principal busca un destino situado entre 300 y 800 metros.
2. Selecciónalo y abre la vista previa.
3. Comprueba que se muestra distancia real, tiempo y número de cruces registrados.
4. Inicia la navegación y camina por la ruta.
5. Verifica que la distancia al siguiente punto cambia con cada posición GPS.
6. Pasa unos metros de un punto sin pisar exactamente sus coordenadas. Debe avanzar al siguiente punto en vez de quedarse bloqueada en “10 m”.
7. Aléjate más de 30 metros de la línea durante al menos cinco segundos. Debe solicitar un recálculo, una sola vez.
8. Activa el modo avión antes de calcular otra ruta. La aplicación debe mostrar **SIN RUTA** y no dibujar una línea recta aproximada.

## 3. Paso de peatones grabado y brújula

1. Graba un paso de peatones marcando **Empieza aquí** en la entrada y **Acaba aquí** en la salida.
2. Reproduce la ruta desde su inicio. Al llegar a la entrada, la aplicación debe anunciar el paso y mantener la ayuda de brújula hasta alcanzar la marca final. La cámara debe permanecer apagada durante la reproducción.
3. Sin entrar en la calzada, gira el móvil unos 30 grados a cada lado. Debe indicar “corrige a la izquierda/derecha”.
4. Al apuntar en el rumbo de la ruta debe mostrar “Rumbo correcto”.
5. Recorre el cruce únicamente en una prueba supervisada. El modo de cruce debe terminar después de avanzar al otro lado, no por un temporizador aislado.

## 4. Ruta grabada

1. Pulsa **GRABAR RUTA** y recorre al menos 100 metros.
2. Marca un cruce, un obstáculo y un giro en lugares distintos.
3. Detén la grabación y abre **MIS RUTAS**.
4. Selecciona la ruta. La vista previa debe mostrar la distancia calculada sobre el rastro GPS, no una estimación por número de eventos.
5. Inicia la ruta desde el punto donde comenzó la grabación y verifica las alertas marcadas.
6. Repite empezando cerca del final. La aplicación debe detectar que corresponde seguirla en sentido inverso.
7. Prueba el botón de eliminar y comprueba que la ruta desaparece de la lista.

## 5. Casos de error esperados

- **GPS antiguo o con error superior a 50 m:** debe pedir que te detengas y esperar una posición fiable.
- **Grabación con menos de dos puntos válidos:** no debe permitir iniciar la navegación.
- **OpenStreetMap sin datos de accesibilidad:** debe avisar que los datos no están verificados.
- **Detector o cámara con error:** debe mostrar “DETECCIÓN NO DISPONIBLE”.
- **Sin Internet:** no debe inventar una ruta.

## Límites de esta versión

- Los pasos de peatones se identifican mediante OpenStreetMap; la cámara todavía no reconoce visualmente las franjas de un paso de cebra.
- El detector incluido distingue obstáculos y algunas categorías generales, pero no identifica con precisión cualquier objeto o vehículo.
- La selección de ruta penaliza escaleras y cruces sin ayudas registradas, pero depende de que OpenStreetMap contenga esos datos.
- La conexión con gafas inteligentes queda preparada como una fuente de cámara futura, pero no se puede validar sin un modelo concreto de gafas y su SDK.

## Resultado automático de esta entrega (20 de septiembre de 2026)

- 49 pruebas unitarias: 49 correctas, 0 fallos.
- Compilación de depuración: correcta.
- Análisis Android Lint: correcto, sin errores bloqueantes.
- APK: `app/build/outputs/apk/debug/app-debug.apk`.
- SHA-256: `6D6DB5F9A0713B248B80BE7C13A714DC24A0AC102AC3732F1E47B593CEFF79F1`.

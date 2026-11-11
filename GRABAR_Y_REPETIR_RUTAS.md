# Grabar y repetir una ruta con botones e instrucciones propias

## Grabar sin dictar: empieza aquí / acaba aquí

1. Abre **Grabar ruta**, espera una ubicación precisa y mantén el móvil apuntando hacia donde vas a caminar.
2. En el inicio del tramo, pulsa uno de estos botones:
   - **Paso de cebra: empieza aquí**.
   - **Recto: empieza aquí**.
   - **Giro a la izquierda: empieza aquí**.
   - **Giro a la derecha: empieza aquí**.
3. Aparecerá el botón **Acaba aquí** con el nombre del tramo. Recorre el tramo y púlsalo en su final. Los demás botones se ocultan mientras está abierto para no mezclar maniobras. **Cancelar este tramo** descarta únicamente esa marca; la ruta continúa grabándose.
4. En pasos de cebra y rectos se guarda el rumbo de brújula **del inicio**, aunque gires el móvil al pulsar el final. En los giros se guardan tanto la posición de inicio y final como el rumbo de salida y el ángulo girado.
5. Continúa marcando tramos y pulsa **Guardar ruta** al terminar. No se permite finalizar la ruta con un tramo pendiente.

Al repetirla, la ayuda de brújula empieza en la marca inicial, corrige hacia la derecha o izquierda si te desvías de la orientación registrada y termina al alcanzar la marca final. Estar mirando correctamente no basta para terminar un paso de cebra: hay que llegar al final marcado. En los giros, primero se comprueba la orientación de salida y después el punto final.

En tramos cortos, el radio de llegada al final se reduce para evitar dar por terminado el paso cuando todavía estás en el inicio. Si el GPS no distingue ambos extremos, puede ser necesario esperar una posición más precisa. No se utiliza la cámara durante esta guía.

## Añadir instrucciones dictadas (opcional)

1. En la pantalla de inicio, pulsa **Grabar ruta**. Espera a tener señal GPS y la brújula calibrada. Sujeta el móvil apuntando hacia donde caminas y conserva esa posición al repetir la ruta.
2. Detente en el punto donde quieres que se anuncie una instrucción. Pulsa **Dictar instrucción** y di, por ejemplo, «Ahora sigue recto hasta la esquina». La ubicación y el rumbo se capturan al pulsar, antes del reconocimiento de voz.
3. Revisa el texto reconocido. Puedes editarlo o pulsar **Escuchar**. **Guardar** conserva el texto revisado; **Cancelar** lo descarta. También existe **Escribir instrucción** si el móvil no dispone de dictado.
4. Para un giro, dicta «Ahora gira a la derecha» estando aún orientado en el sentido de entrada. Guarda la frase, realiza el giro y pulsa **Marcar final del giro**, con el móvil apuntando hacia el nuevo tramo. La aplicación guarda el rumbo de salida y anuncia el ángulo medido. No acepta un giro casi nulo o contrario al lado dictado.
5. Para un tramo recto dictado, incluye «recto» en la instrucción; para un cruce dictado, di «Ahora paso de cebra». Apunta el móvil hacia el tramo que quieres seguir al pulsar dictar: ese es el rumbo que se guarda. Usa los botones anteriores cuando quieras guardar también un final explícito.
6. Pulsa **Guardar ruta** en el destino. Si hay un giro pendiente, termina de marcarlo primero. Se espera a guardar el punto final antes de cerrar.
7. Abre la ruta guardada desde el inicio del recorrido. Las rutas con instrucciones mantienen el sentido en que se grabaron. Se dicen las frases revisadas en orden, sin traducirlas ni resumirlas. Toca la instrucción actual para repetir el último aviso.

Se guarda el **texto reconocido**, no una grabación de tu voz. El servicio de dictado instalado en el móvil puede necesitar conexión.

## Comportamiento de la guía

- Cada instrucción se activa tras dos posiciones GPS distintas cerca de su punto. La tolerancia configurada es de 4 a 8 metros, según la precisión informada por el móvil; no representa una garantía de precisión física.
- La reproducción se pausa si el GPS informa de más de 12 metros de error, la posición tiene más de 15 segundos o la brújula no informa de una precisión suficiente.
- Los giros con rumbo registrado no se dan por terminados hasta mantener una orientación dentro de ±15 grados durante 1,5 segundos. Los avisos indican hacia qué lado corregir y los grados aproximados restantes.
- Los rectos y cruces grabados con botones conservan el rumbo hasta su final explícito. Las instrucciones dictadas sin final conservan el comportamiento anterior: su referencia termina al llegar a la siguiente instrucción.
- Los textos cercanos entre sí se conservan por separado. La hora de grabación distingue las visitas a un mismo punto en una ruta con bucles. Si se pierde el seguimiento, no se saltan automáticamente instrucciones pendientes.
- La cámara y la detección de objetos solo se activan en **Exploración / protección de obstáculos**. Los cruces de navegación no encienden la cámara.
- Las rutas anteriores se conservan mediante una migración de base de datos. Los giros antiguos sin rumbo de salida no disponen de medición angular: hay que volver a grabarlos para obtenerla.

## Límites y prueba en el móvil

La brújula mide la orientación del móvil. Mantener un rumbo no demuestra que se siga una línea exacta dentro de la acera o del paso de cebra; tampoco comprueba el tráfico ni autoriza a cruzar. Cambiar la posición del teléfono respecto al cuerpo altera esta ayuda.

Primero prueba acompañado, en un recorrido controlado sin tráfico:

1. Graba un tramo recto, un giro a la derecha y otro recto. Comprueba el texto antes de guardarlo.
2. Repite desde el inicio. Verifica que la instrucción del giro no se anuncia al completar el punto anterior, y que se mantiene pendiente mientras sigues mirando en el rumbo de entrada.
3. Desvía suavemente el móvil a ambos lados y comprueba el sentido de las correcciones.
4. Graba dos notas en un mismo sitio y un recorrido que vuelva a pasar por ese sitio. Comprueba el orden y que no se anticipa la segunda visita.
5. Comprueba la pausa por GPS/brújula imprecisos, la cancelación del dictado y la persistencia tras cerrar y volver a abrir la aplicación.
6. Comprueba con TalkBack los botones de dictado, revisión, final del giro y repetición; verifica que la cámara permanece apagada durante la grabación y reproducción.
7. Graba un recto y un paso de cebra con los botones de inicio y final. Repite el recorrido: comprueba que la ayuda permanece activa a mitad del tramo y termina en la marca final. Prueba también un paso corto para comprobar que no termina desde la entrada.
8. Inicia un tramo, cierra/recrea la pantalla y comprueba que sigue pendiente su final. Cancélalo y verifica que puedes marcar otro inicio. Actualiza una instalación anterior y comprueba que conserva sus rutas.

Las pruebas automáticas validan las reglas de reproducción y la construcción de rutas. La precisión real, el reconocimiento de voz, TalkBack y la migración sobre una instalación existente necesitan comprobación en un dispositivo Android.

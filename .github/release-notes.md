**RideScreen AA** es Android Auto inalámbrico en el cuadro de tu moto (cuadros EasyConn/Carbit), con arreglos y extras pensados para las **Zontes 125X / ZT125T-X**. Es la continuación de la versión no oficial de OpenCfMoto que se ha ido compartiendo en el grupo, ahora con nombre, diseño e icono propios.

Basada en [OpenCfMoto](https://github.com/zanderp/open-cfmoto) de Alexandru (zanderp). No es una app oficial de Zontes ni de CFMoto. Código fuente en este repositorio, licencia AGPL-3.0.

### Novedades de la v1.2
- **🅿️ ¿Dónde he aparcado?** Al desconectarte del cuadro, la app guarda dónde has dejado la moto. Lo verás en la pantalla principal, en el reloj y en un **widget** para la pantalla de inicio; un toque y Maps te lleva andando.
- **📸 Comparte tus viajes.** En *Viajes*, el botón **Compartir** crea una imagen con el recorrido, la foto de tu moto y los números del viaje, lista para WhatsApp o Instagram.
- **🗺 Todo lo rodado.** Todos tus viajes juntos en un mapa (y también se puede compartir).
- **🌧️ Aviso de lluvia.** Al conectar con la moto, si va a llover en las próximas 3 horas te avisa en el móvil y en el reloj.
- **📍 Compartir ruta en directo.** Un enlace temporal para que tu familia vea por dónde vas. Se para solo al acabar el viaje y se borra al día siguiente.
- **⌚ Reloj:** nuevo *tile* y complicación para la esfera con los km del viaje, el aparcamiento en la pantalla de información y la próxima maniobra en la pantalla del viaje.
- **⌚ Vibraciones de giro (experimental).** Con la navegación de Android Auto, el reloj vibra antes de cada giro: 2 toques izquierda, 3 derecha, uno largo + N cortos para la salida N de una rotonda. Se activa en *Ajustes → Extras de ruta*.

### Instalación en el móvil
1. Descarga **`RideScreen-AA-v1.2.apk`** (abajo, en *Assets*).
2. Si vienes de la **v1/v1.1** o de la versión del grupo (zontes-1/2), se instala **encima** sin perder nada. Si tienes la OpenCfMoto **oficial**, desinstálala primero: están firmadas con claves distintas.
3. Si sale el aviso de **Play Protect** ("app no segura" o "bloqueada"), es porque Google aún no conoce la app: **Más detalles → Instalar de todas formas**.
4. Las próximas versiones te las ofrecerá la propia app.

### Instalación en el reloj (opcional, Wear OS)
El reloj no instala APK directamente: se hace por depuración inalámbrica, una sola vez.
1. En el reloj: Ajustes → Información del reloj → Software → toca **Versión de software** 5 veces (activa las opciones de desarrollador).
2. Ajustes → Conexiones → **Wi-Fi en "Activado"** (no Automático), en la misma red que el móvil. Si no te muestra IP, desactiva un momento el Bluetooth del móvil.
3. Opciones de desarrollador → **Depuración ADB** y **Depuración inalámbrica**. Sube el tiempo de pantalla para que no se apague mientras emparejas.
4. Desde el móvil, con **Wear Installer 2** o **Bugjaeger** (Play Store): empareja con el código de "Emparejar nuevo dispositivo" y luego instala **`RideScreen-AA-v1.2-WearOS-reloj.apk`**. Ojo: el puerto para emparejar y el de conectar son distintos.

### Si algo falla
Desde la app, **Compartir registros** y pásalo por el grupo contando qué ha pasado. Los registros ya ocultan números de serie y contraseñas.

**RideScreen AA** es Android Auto inalámbrico en el cuadro de tu moto (cuadros EasyConn/Carbit), con arreglos y extras pensados para las **Zontes 125X / ZT125T-X**. Es la continuación de la versión no oficial de OpenCfMoto que se ha ido compartiendo en el grupo, ahora con nombre, diseño e icono propios.

Basada en [OpenCfMoto](https://github.com/zanderp/open-cfmoto) de Alexandru (zanderp). No es una app oficial de Zontes ni de CFMoto. Código fuente en este repositorio, licencia AGPL-3.0.

### Novedades de la v1
- **Nuevo diseño:** verde mate y dorado, con la **foto de tu moto** en la pantalla principal (ponla en Garaje), los km y la velocidad máxima del día, y el botón Conectar siempre a mano abajo.
- **Reloj del cuadro siempre en hora:** si al conectar el cuadro se queda a 00:00, la app lo detecta y lo corrige sola **en unos 3 segundos** (reconecta un momento; verás "⏱ Ajustando hora…"). No hace falta tocar nada.
- **App para relojes Wear OS (opcional):** se abre sola al conectar con la moto y muestra el viaje en directo (velocidad, km, tiempo, máxima y media). Además trae una cruceta para manejar Android Auto (con el **bisel del Galaxy Watch Classic** como rueda), una pantalla con hora, altitud, rumbo y batería del móvil, y tus **últimos viajes con su mapa** (zoom con el bisel o con los dedos).
- **Volumen según la velocidad** (en Controles, desactivado por defecto).
- **Los botones del manillar controlan la música por defecto.** Si quieres que muevan Android Auto, actívalo en Controles → *Los botones del manillar controlan Android Auto*.

### Instalación en el móvil
1. Descarga **`RideScreen-AA-v1.apk`** (abajo, en *Assets*).
2. Si vienes de la versión del grupo (zontes-1/2), se instala **encima** sin perder nada. Si tienes la OpenCfMoto **oficial**, desinstálala primero: están firmadas con claves distintas.
3. Si sale el aviso de **Play Protect** ("app no segura" o "bloqueada"), es porque Google aún no conoce la app: **Más detalles → Instalar de todas formas**.
4. Las próximas versiones te las ofrecerá la propia app.

### Instalación en el reloj (opcional, Wear OS)
El reloj no instala APK directamente: se hace por depuración inalámbrica, una sola vez.
1. En el reloj: Ajustes → Información del reloj → Software → toca **Versión de software** 5 veces (activa las opciones de desarrollador).
2. Ajustes → Conexiones → **Wi-Fi en "Activado"** (no Automático), en la misma red que el móvil. Si no te muestra IP, desactiva un momento el Bluetooth del móvil.
3. Opciones de desarrollador → **Depuración ADB** y **Depuración inalámbrica**. Sube el tiempo de pantalla para que no se apague mientras emparejas.
4. Desde el móvil, con **Wear Installer 2** o **Bugjaeger** (Play Store): empareja con el código de "Emparejar nuevo dispositivo" y luego instala **`RideScreen-AA-v1-WearOS-reloj.apk`**. Ojo: el puerto para emparejar y el de conectar son distintos.

### Si algo falla
Desde la app, **Compartir registros** y pásalo por el grupo contando qué ha pasado. Los registros ya ocultan números de serie y contraseñas.

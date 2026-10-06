**RideScreen AA** es Android Auto inalámbrico en el cuadro de tu moto (cuadros EasyConn/Carbit), con arreglos y extras pensados para las **Zontes 125X / ZT125T-X**. Es la continuación de la versión no oficial de OpenCfMoto que se ha ido compartiendo en el grupo, ahora con nombre, diseño e icono propios.

Basada en [OpenCfMoto](https://github.com/zanderp/open-cfmoto) de Alexandru (zanderp). No es una app oficial de Zontes ni de CFMoto. Código fuente en este repositorio, licencia AGPL-3.0.

### Novedades de la v1.1
- **Los informes de errores ahora nos llegan a nosotros.** Si la app se cierra de golpe o falla la conexión, se envía un informe **anónimo** (un número aleatorio, la versión de la app y de Android, y el texto del error, sin contraseñas ni números de serie) al servidor de RideScreen AA, y así nos enteramos al momento para arreglarlo. Hasta la v1 iban al proyecto original de OpenCfMoto. Ni rutas, ni GPS, ni nombre de la moto, ni IP. Se puede desactivar en **Ajustes → Privacidad**. Detalles en [PRIVACY.md](https://github.com/mgb1982/open-cfmoto/blob/main/PRIVACY.md).

Todo lo de la v1 sigue igual: diseño verde mate y dorado, reloj del cuadro que se corrige solo, app para Wear OS y volumen según la velocidad.

### Instalación en el móvil
1. Descarga **`RideScreen-AA-v1.1.apk`** (abajo, en *Assets*).
2. Si vienes de la **v1** o de la versión del grupo (zontes-1/2), se instala **encima** sin perder nada. Si tienes la OpenCfMoto **oficial**, desinstálala primero: están firmadas con claves distintas.
3. Si sale el aviso de **Play Protect** ("app no segura" o "bloqueada"), es porque Google aún no conoce la app: **Más detalles → Instalar de todas formas**.
4. Las próximas versiones te las ofrecerá la propia app.

### Instalación en el reloj (opcional, Wear OS)
El reloj no instala APK directamente: se hace por depuración inalámbrica, una sola vez.
1. En el reloj: Ajustes → Información del reloj → Software → toca **Versión de software** 5 veces (activa las opciones de desarrollador).
2. Ajustes → Conexiones → **Wi-Fi en "Activado"** (no Automático), en la misma red que el móvil. Si no te muestra IP, desactiva un momento el Bluetooth del móvil.
3. Opciones de desarrollador → **Depuración ADB** y **Depuración inalámbrica**. Sube el tiempo de pantalla para que no se apague mientras emparejas.
4. Desde el móvil, con **Wear Installer 2** o **Bugjaeger** (Play Store): empareja con el código de "Emparejar nuevo dispositivo" y luego instala **`RideScreen-AA-v1.1-WearOS-reloj.apk`**. Ojo: el puerto para emparejar y el de conectar son distintos.

### Si algo falla
Desde la app, **Compartir registros** y pásalo por el grupo contando qué ha pasado. Los registros ya ocultan números de serie y contraseñas.

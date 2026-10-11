**RideScreen AA** es Android Auto inalámbrico en el cuadro de tu moto (cuadros EasyConn/Carbit), con arreglos y extras pensados para las **Zontes 125X / ZT125T-X**. Es la continuación de la versión no oficial de OpenCfMoto que se ha ido compartiendo en el grupo, ahora con nombre, diseño e icono propios.

Basada en [OpenCfMoto](https://github.com/zanderp/open-cfmoto) de Alexandru (zanderp). No es una app oficial de Zontes ni de CFMoto. Código fuente en este repositorio, licencia AGPL-3.0.

### Novedades de la v2.1
- **⛽ Gasolineras cerca con precio de la Gasolina 95**: botón nuevo en la pantalla principal. Ordénalas por **más baratas** o **más cercanas**; toca una y Maps te lleva hasta ella en el cuadro. Precios oficiales del Ministerio, actualizados cada hora (solo España).
- **📸 Aparcar con foto** (opcional): mientras estás conectado aparece un botón que hace una foto de dónde dejas la moto y luego desconecta y guarda el sitio. **Aturar** sigue funcionando igual, sin foto. Desde el botón 🅿️ puedes ver la foto, repetirla o añadirla más tarde.
- **❓ Botón Ayuda**: guía de instalación con capturas, compartir registros, novedades e informar de un problema.
- **✨ Pantalla de novedades** al actualizar.
- **🔌 Primera conexión más fácil**: el permiso de "Dispositivos cercanos", el Bluetooth y la Wi-Fi apagados, y la pregunta de Android para conectar a la Wi-Fi de la moto se explican paso a paso, y la conexión sigue sola al volver.
- **🧭 Registro de viajes**: si lo tienes desactivado, la pantalla de viajes te avisa y te deja reactivarlo con un toque.
- **🎞️ "Más fluido" ahora se llama "Ahorro de datos (menos nitidez)"**: es lo que hace de verdad. Si ves la imagen pixelada, elige otra calidad.
- Correcciones de estabilidad.

**Y todo lo de la v2**: mapa de calor, récords y trofeos, nombres automáticos de viajes, tu año en moto, libreta de mantenimiento y repostajes, aparcamiento al apagar la moto, conexión automática por Bluetooth y brújula hacia tu moto en el reloj.

### Instalación en el móvil
1. Descarga **`RideScreen-AA-v2.1.apk`** (abajo, en *Assets*).
2. Si vienes de la **v1/v1.1/v2** o de la versión del grupo (zontes-1/2), se instala **encima** sin perder nada. Si tienes la OpenCfMoto **oficial**, desinstálala primero: están firmadas con claves distintas.
3. Si sale el aviso de **Play Protect** ("app no segura" o "bloqueada"), es porque Google aún no conoce la app: **Más detalles → Instalar de todas formas**.
4. Las próximas versiones te las ofrecerá la propia app.

### Instalación en el reloj (opcional, Wear OS)
El reloj no instala APK directamente: se hace por depuración inalámbrica, una sola vez.
1. En el reloj: Ajustes → Información del reloj → Software → toca **Versión de software** 5 veces (activa las opciones de desarrollador).
2. Ajustes → Conexiones → **Wi-Fi en "Activado"** (no Automático), en la misma red que el móvil. Si no te muestra IP, desactiva un momento el Bluetooth del móvil.
3. Opciones de desarrollador → **Depuración ADB** y **Depuración inalámbrica**. Sube el tiempo de pantalla para que no se apague mientras emparejas.
4. Desde el móvil, con **Wear Installer 2** o **Bugjaeger** (Play Store): empareja con el código de "Emparejar nuevo dispositivo" y luego instala **`RideScreen-AA-v2.1-WearOS-reloj.apk`**. Ojo: el puerto para emparejar y el de conectar son distintos.

### Si algo falla
Desde la app, **Compartir registros** y pásalo por el grupo contando qué ha pasado. Los registros ya ocultan números de serie y contraseñas.

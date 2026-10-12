**RideScreen AA** es Android Auto inalámbrico en el cuadro de tu moto (cuadros EasyConn/Carbit), con arreglos y extras pensados para las **Zontes 125X / ZT125T-X**. Es la continuación de la versión no oficial de OpenCfMoto que se ha ido compartiendo en el grupo, ahora con nombre, diseño e icono propios.

Basada en [OpenCfMoto](https://github.com/zanderp/open-cfmoto) de Alexandru (zanderp). No es una app oficial de Zontes ni de CFMoto. Código fuente en este repositorio, licencia AGPL-3.0.

### Novedades de la v2.2
**Seguridad**
- **🆘 Detector de caídas** (opcional, en *Configuración → Extras de ruta*): si el móvil nota un golpe fuerte y después la moto se queda parada, suena una alarma de 60 s en el móvil y en el reloj. Si nadie pulsa **«Estoy bien»**, envía un SMS con tu ubicación a hasta 3 contactos, y otro a los 5 minutos. Tiene botón para simularlo y SMS de prueba. Nunca llama a emergencias por su cuenta. No es un dispositivo certificado.

**Conexión**
- **🔌 Vuelve a conectar sola al encender la moto**: pasados 20 minutos sin la moto, en vez de rendirse, sigue esperando su Wi-Fi en **modo ahorro** y conecta cuando la enciendes. La conexión por Bluetooth del cuadro ahora solo arranca si la Wi-Fi de la moto está encendida (el Bluetooth del cuadro sigue activo con la moto apagada).
- **🎙️ «Ok Google» y los botones de voz** del intercomunicador y del reloj vuelven a funcionar: la app pide el micrófono al conectar (elige *«Mientras se usa la app»*).
- **🔌 Primera conexión más fácil**: el permiso de "Dispositivos cercanos", el Bluetooth y la Wi-Fi apagados, y la pregunta de Android para conectar a la Wi-Fi de la moto se explican paso a paso.
- **🎯 Ajustar la pantalla al cuadro** con flechas, viendo el resultado **en directo en la moto** (un marco amarillo marca la zona de Android Auto). La medida manual del panel sigue a la vista, también para paneles verticales.

**En ruta**
- **⛽ Gasolineras cerca con el precio de la Gasolina 95**: ordénalas por más baratas o más cercanas; toca una y Maps te lleva en el cuadro. Precios oficiales del Ministerio, cada hora (solo España).
- **📸 Aparcar con foto** (opcional): mientras estás conectado, haz una foto de dónde dejas la moto y la app desconecta y guarda el sitio. **Detener** sigue igual. Desde el botón 🅿️ puedes ver, repetir o añadir la foto.

**Tus viajes**
- **🚦 Tus semáforos**: mapa de los semáforos donde más paras, cuánto esperas y, cuando haces el mismo trayecto por caminos distintos, cuál es de verdad más rápido.
- **🎬 Vídeo del viaje**: junta los clips que grabaste durante un viaje (Ray-Ban Meta importados con la app Meta AI, cámara de acción, móvil…) con la ruta, la velocidad y la hora encima. Velocidad normal o rápido ×4.

**Reloj**
- **⌚ Relojes sin bisel**: ▲ y ▼ de la cruceta ya recorren los menús de Android Auto (mantén pulsado para la flecha de antes).

**Y además**: botón **❓ Ayuda** (guía, registros, novedades, problemas), pantalla de **novedades**, aviso si tienes el registro de viajes desactivado, «Más fluido» pasa a llamarse **«Ahorro de datos (menos nitidez)»**, y correcciones de estabilidad.

**Y todo lo de la v2**: mapa de calor, récords y trofeos, nombres automáticos de viajes, tu año en moto, libreta de mantenimiento y repostajes, aparcamiento al apagar la moto y brújula hacia tu moto en el reloj.

### Instalación en el móvil
1. Descarga **`RideScreen-AA-v2.2.apk`** (abajo, en *Assets*).
2. Si vienes de la **v1/v1.1/v2** o de la versión del grupo (zontes-1/2), se instala **encima** sin perder nada. Si tienes la OpenCfMoto **oficial**, desinstálala primero: están firmadas con claves distintas.
3. Si sale el aviso de **Play Protect** ("app no segura" o "bloqueada"), es porque Google aún no conoce la app: **Más detalles → Instalar de todas formas**.
4. Las próximas versiones te las ofrecerá la propia app.

### Instalación en el reloj (opcional, Wear OS)
El reloj no instala APK directamente: se hace por depuración inalámbrica, una sola vez.
1. En el reloj: Ajustes → Información del reloj → Software → toca **Versión de software** 5 veces (activa las opciones de desarrollador).
2. Ajustes → Conexiones → **Wi-Fi en "Activado"** (no Automático), en la misma red que el móvil. Si no te muestra IP, desactiva un momento el Bluetooth del móvil.
3. Opciones de desarrollador → **Depuración ADB** y **Depuración inalámbrica**. Sube el tiempo de pantalla para que no se apague mientras emparejas.
4. Desde el móvil, con **Wear Installer 2** o **Bugjaeger** (Play Store): empareja con el código de "Emparejar nuevo dispositivo" y luego instala **`RideScreen-AA-v2.2-WearOS-reloj.apk`**. Ojo: el puerto para emparejar y el de conectar son distintos.

### Si algo falla
Desde la app, **Compartir registros** y pásalo por el grupo contando qué ha pasado. Los registros ya ocultan números de serie y contraseñas.

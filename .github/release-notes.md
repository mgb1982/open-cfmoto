**RideScreen AA** es Android Auto inalámbrico en el cuadro de tu moto (cuadros EasyConn/Carbit), con arreglos y extras pensados para las **Zontes 125X / ZT125T-X**. Es la continuación de la versión no oficial de OpenCfMoto que se ha ido compartiendo en el grupo, ahora con nombre, diseño e icono propios.

Basada en [OpenCfMoto](https://github.com/zanderp/open-cfmoto) de Alexandru (zanderp). No es una app oficial de Zontes ni de CFMoto. Código fuente en este repositorio, licencia AGPL-3.0.

### Novedades de la v2
**Mapas y viajes**
- **🔥 Mapa de calor**: en *Viajes → 🗺 Todo*, elige **Rutas** o **Calor** y el periodo (**mes, año o siempre**). En rojo, por donde más ruedas. Y se puede compartir.
- **Rutas mucho más visibles**, con contorno y el mapa de fondo apagado.
- **Cada viaje coloreado por velocidad** (azul lento → rojo rápido).
- **Nombres automáticos**: "Sants → Zona Franca" en vez de solo la fecha (también en el reloj).
- **🏆 Récords**: viaje más largo, velocidad máxima, más km en un día y racha de días seguidos, con aviso cuando bates uno.
- **🎖️ Trofeos desbloqueables**: kilómetros totales, gran ruta, velocidad punta, días seguidos, número de viajes, horas de moto, madrugador, nocturno, finde motero… Con fecha de cuando lo conseguiste, barra de progreso hacia el siguiente y aviso al desbloquear (también en el reloj). Toca la tarjeta de récords en *Viajes*.
- **📅 Tu año en moto**: tus números del año o del mes en tarjetas tipo historias, listas para compartir.

**Tu moto**
- **🔧 Libreta de mantenimiento**: cuentakilómetros estimado, aceite, correa, frenos, ITV, seguro… con avisos al conectar con la moto. Y **⛽ repostajes** con tu consumo real.
- **🅿️ El aparcamiento se guarda al apagar la moto**, sin tener que pulsar Aturar.
- **🔋 Menos batería con la moto apagada**: si la moto no aparece en 20 minutos, la app deja de buscarla y te avisa.
- **⚠️ Aviso del servidor de unidad principal de Android Auto**: si no está iniciado, la app te lo dice antes de conectar y te lleva directo a sus ajustes.

**Reloj**
- **🧭 Brújula hacia tu moto**: una flecha que apunta a donde aparcaste, con la distancia, usando el GPS del propio reloj.
- Más todo lo de la v1.2: aparcamiento, tile y complicación, vibraciones de giro (experimental) y próxima maniobra.

**Y todo lo de la v1.2**: compartir ruta en directo, aviso de lluvia, tarjetas para compartir, widget, botón de donar y agradecimientos a Alexandru (zanderp), sin quien nada de esto existiría.

### Instalación en el móvil
1. Descarga **`RideScreen-AA-v2.apk`** (abajo, en *Assets*).
2. Si vienes de la **v1/v1.1** o de la versión del grupo (zontes-1/2), se instala **encima** sin perder nada. Si tienes la OpenCfMoto **oficial**, desinstálala primero: están firmadas con claves distintas.
3. Si sale el aviso de **Play Protect** ("app no segura" o "bloqueada"), es porque Google aún no conoce la app: **Más detalles → Instalar de todas formas**.
4. Las próximas versiones te las ofrecerá la propia app.

### Instalación en el reloj (opcional, Wear OS)
El reloj no instala APK directamente: se hace por depuración inalámbrica, una sola vez.
1. En el reloj: Ajustes → Información del reloj → Software → toca **Versión de software** 5 veces (activa las opciones de desarrollador).
2. Ajustes → Conexiones → **Wi-Fi en "Activado"** (no Automático), en la misma red que el móvil. Si no te muestra IP, desactiva un momento el Bluetooth del móvil.
3. Opciones de desarrollador → **Depuración ADB** y **Depuración inalámbrica**. Sube el tiempo de pantalla para que no se apague mientras emparejas.
4. Desde el móvil, con **Wear Installer 2** o **Bugjaeger** (Play Store): empareja con el código de "Emparejar nuevo dispositivo" y luego instala **`RideScreen-AA-v2-WearOS-reloj.apk`**. Ojo: el puerto para emparejar y el de conectar son distintos.

### Si algo falla
Desde la app, **Compartir registros** y pásalo por el grupo contando qué ha pasado. Los registros ya ocultan números de serie y contraseñas.

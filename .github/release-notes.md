Versión no oficial de [OpenCfMoto](https://github.com/zanderp/open-cfmoto) (Android Auto inalámbrico en el cuadro) con arreglos para las **Zontes 125X / ZT125T-X**. La mantiene un usuario, no zanderp. El código fuente está en este repositorio (licencia AGPL-3.0).

### Qué cambia respecto a la oficial
- **El reloj del cuadro ya no se pone a 00:00 / 13:49 al conectar.** La app replica dos mensajes que envía la app oficial de Zontes. Si detecta que el cuadro ha pedido la hora tarde (la señal de que no la ha cogido), reconecta sola unos 10 s después, hasta 3 veces. Durante esos ~3 s la imagen del cuadro se corta: es normal. En la mayoría de trayectos no hace falta y no verás ningún corte.
- **Volumen según la velocidad** (desactivado por defecto, en Controles): sube el volumen multimedia del móvil (navegación y música) a medida que vas más rápido, para compensar el ruido del viento. Niveles Bajo / Medio / Alto o curva personalizada. Si cambias el volumen a mano, lo respeta como nueva base.

### Instalación
1. Descarga el archivo `.apk` de abajo (en *Assets*).
2. Si tienes instalada la versión oficial de OpenCfMoto, **desinstálala primero**: al estar firmadas con claves distintas, Android no deja instalar una encima de la otra.
3. Ábrelo y permite la instalación desde tu navegador o gestor de archivos si te lo pide.
   - Puede salir el aviso de **Play Protect** de "app no segura" o "app bloqueada". Es porque la app es nueva y Google aún no la conoce, no porque haga nada raro. Pulsa **Más detalles → Instalar de todas formas**. El código fuente es público en este repositorio.
4. Las próximas versiones de esta página se instalarán encima, sin perder la configuración.

### Ajustes recomendados
- Ajustes → **Clock lab (EXPERIMENTAL)** (en catalán, *Laboratori del rellotge*): preset **Última**, y **Resincronizar el reloj del cuadro (Zontes)** en **Inteligente** (viene así por defecto).

### Si algo falla
Desde la app, **Compartir registros** y pásalo por el grupo, indicando qué hora mostraba el cuadro antes de conectar y después. Los registros ya ocultan los números de serie y las contraseñas.

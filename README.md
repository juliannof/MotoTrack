# 🏍️ MotoTrack — App Android para grabación de rutas en moto

App nativa Android en **Kotlin** que registra en tiempo real:
- 📍 **GPS** — latitud, longitud, altitud, precisión
- ⚡ **Velocidad** — km/h derivada del GPS (más precisa que acelerómetro)
- 🔄 **Aceleración** — ejes X/Y/Z y magnitud resultante (sin gravedad)
- 📐 **Lean Angle** — ángulo de inclinación lateral mediante sensor de rotación

---

## 📁 Estructura del proyecto

```
MotoTrack/
├── app/src/main/
│   ├── java/com/mototrack/
│   │   ├── data/
│   │   │   ├── Route.kt              ← Entidad "ruta completa"
│   │   │   ├── RoutePoint.kt         ← Entidad "punto GPS + sensores"
│   │   │   ├── RouteDao.kt           ← Consultas Room
│   │   │   ├── MotoTrackDatabase.kt  ← Base de datos Room
│   │   │   └── RouteRepository.kt    ← Capa de acceso a datos
│   │   ├── service/
│   │   │   └── TrackingService.kt    ← Servicio foreground (GPS + sensores)
│   │   ├── ui/
│   │   │   ├── MainActivity.kt
│   │   │   ├── MainViewModel.kt
│   │   │   ├── DashboardFragment.kt  ← HUD en tiempo real
│   │   │   ├── HistoryFragment.kt    ← Lista de rutas
│   │   │   ├── RouteDetailFragment.kt ← Gráficas + estadísticas
│   │   │   └── RouteAdapter.kt
│   │   └── utils/
│   │       └── GpxExporter.kt        ← Exportar a formato GPX
│   └── res/...
```

---

## 🚀 Cómo importar en Android Studio

1. Abre **Android Studio → File → Open**
2. Selecciona la carpeta `MotoTrack/`
3. Espera a que sincronice Gradle
4. Conecta tu dispositivo Android (o emulador con GPS virtual)
5. Ejecuta con ▶️ Run

> **Requisitos mínimos:** Android 8.0 (API 26) | Android Studio Hedgehog o superior

### 🗺️ API key de Google Maps

El mapa del detalle de ruta necesita una API key de **Maps SDK for Android**.
Añádela a `local.properties` (no se sube a git):

```properties
MAPS_API_KEY=tu_api_key
```

Sin ella la app compila y funciona, pero el mapa sale gris.

---

## 📡 Sensores utilizados

| Sensor | Uso |
|--------|-----|
| `GPS_PROVIDER` | Posición, velocidad, bearing cada ~1s |
| `TYPE_ACCELEROMETER` | Aceleración bruta (con filtro de gravedad) |
| `TYPE_ROTATION_VECTOR` | Lean angle preciso via matriz de rotación |

---

## 🗄️ Modelo de datos (Room)

### Route
| Campo | Tipo | Descripción |
|-------|------|-------------|
| id | Long | Clave primaria |
| name | String | Nombre de la ruta |
| startTime / endTime | Long | Timestamps ms |
| distanceKm | Float | Distancia total |
| maxSpeedKmh | Float | Velocidad máxima |
| maxLeanAngle | Float | Inclinación máxima |
| maxAcceleration | Float | Aceleración máxima |

### RoutePoint
| Campo | Tipo | Descripción |
|-------|------|-------------|
| latitude / longitude | Double | Posición GPS |
| altitude | Double | Altitud en metros |
| speedKmh | Float | Velocidad (GPS) |
| accelX/Y/Z | Float | Aceleración por eje |
| accelTotal | Float | Magnitud resultante |
| leanAngle | Float | Inclinación lateral |
| bearing | Float | Orientación (grados) |

---

## 📤 Exportación GPX

Las rutas se pueden exportar al formato estándar **GPX 1.1**,  
compatible con Strava, Garmin Connect, Google Earth, etc.

Las extensiones GPX incluyen `leanAngle` y `accelTotal` por punto.

---

## 🔧 Permisos requeridos

- `ACCESS_FINE_LOCATION` — GPS preciso
- `FOREGROUND_SERVICE_LOCATION` — Grabación en background
- `POST_NOTIFICATIONS` — Notificación del servicio activo
- `WAKE_LOCK` — Mantener CPU activa durante grabación

---

## 📈 Próximas mejoras sugeridas

- [x] Mapa del recorrido con Google Maps
- [ ] Gráfica G-Force circular (como los MotoGP)
- [ ] Alertas de velocidad máxima
- [ ] Integración con Bluetooth OBD2 (RPM, temperatura)
- [ ] Análisis de curvas automático
- [ ] Modo pantalla siempre encendida

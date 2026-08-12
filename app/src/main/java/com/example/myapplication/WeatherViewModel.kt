package com.example.myapplication

import android.content.Context
import android.location.Geocoder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.min

class WeatherViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(WeatherUiState())
    val uiState: StateFlow<WeatherUiState> = _uiState.asStateFlow()

    private val _suggestions = MutableStateFlow<List<GeoResult>>(emptyList())
    val suggestions: StateFlow<List<GeoResult>> = _suggestions.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChanged(query: String) {
        searchJob?.cancel()
        if (query.length < 2) {
            _suggestions.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            runCatching {
                WeatherClient.geocodingApi.search(query)
            }.onSuccess { resp ->
                _suggestions.value = resp.results ?: emptyList()
            }.onFailure {
                _suggestions.value = emptyList()
            }
        }
    }

    fun clearSuggestions() {
        _suggestions.value = emptyList()
    }

    fun selectPlace(place: GeoResult) {
        loadWeather(place.latitude, place.longitude, buildLabel(place.name, place.admin1, place.country))
    }

    fun searchAndLoad(query: String) {
        viewModelScope.launch {
            runCatching {
                WeatherClient.geocodingApi.search(query, count = 1)
            }.onSuccess { resp ->
                val place = resp.results?.firstOrNull()
                if (place != null) {
                    loadWeather(place.latitude, place.longitude, buildLabel(place.name, place.admin1, place.country))
                } else {
                    _uiState.value = _uiState.value.copy(error = "No city found for \"$query\"")
                }
            }.onFailure {
                _uiState.value = _uiState.value.copy(error = "Search failed. Check your connection.")
            }
        }
    }

    fun loadByCoordinates(context: Context, latitude: Double, longitude: Double) {
        viewModelScope.launch {
            val label = runCatching {
                val geocoder = Geocoder(context, Locale.getDefault())
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                val address = addresses?.firstOrNull()
                address?.locality ?: address?.subAdminArea ?: address?.adminArea ?: "Current Location"
            }.getOrNull() ?: "Current Location"

            loadWeather(latitude, longitude, label)
        }
    }

    private var refreshJob: Job? = null

    private fun loadWeather(
        latitude: Double,
        longitude: Double,
        label: String
    ) {
        refreshJob?.cancel()

        refreshJob = viewModelScope.launch {

            while (true) {

                try {
                    // Show loading only on the first load
                    if (_uiState.value.current == null) {
                        _uiState.value = WeatherUiState(
                            isLoading = true,
                            locationLabel = label
                        )
                    }

                    // Get latest weather from Open-Meteo
                    val forecast =
                        WeatherClient.forecastApi.getForecast(
                            latitude = latitude,
                            longitude = longitude
                        )

                    // Get latest air quality
                    val aqi = runCatching {
                        WeatherClient.airQualityApi
                            .getAirQuality(
                                latitude = latitude,
                                longitude = longitude
                            )
                            .current?.usAqi
                    }.getOrNull()

                    // Update existing UI
                    _uiState.value =
                        buildUiState(
                            label = label,
                            w = forecast,
                            aqi = aqi
                        )

                } catch (e: Exception) {

                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Couldn't update the weather. Please check your connection."
                    )
                }

                // Refresh every 10 minutes
                delay(10 * 60 * 1000L)
            }
        }
    }
    private fun buildUiState(label: String, w: ForecastResponse, aqi: Int?): WeatherUiState {
        // Truncate "now" to the hour before comparing against hourly slots
        // (which are HH:00 only). Comparing full HH:mm strings used to skip
        // the current hour and label the *next* hour "Now" by mistake.
        val nowHourIso = w.current.time.substring(0, 13) + ":00"
        val startIdx = w.hourly.time.indexOfFirst { it >= nowHourIso }.let { if (it < 0) 0 else it }
        val endIdx = min(startIdx + 8, w.hourly.time.size)

        val hourSlots = (startIdx until endIdx).map { i ->
            // Use the hour's own is_day flag when available, falling back to
            // the current observation's is_day. Previously this was hardcoded
            // to true, so every hour showed a daytime icon even at night.
            val hourIsDay = w.hourly.isDay.getOrNull(i)?.let { it == 1 } ?: (w.current.isDay == 1)
            val (_, icon) = codeInfo(w.hourly.weatherCode[i], isDay = hourIsDay)
            HourSlot(
                label = if (i == startIdx) "Now" else fmtHour(w.hourly.time[i]),
                temp = w.hourly.temperature[i].toInt(),
                icon = icon,
                rainChance = w.hourly.precipitationProbability.getOrNull(i) ?: 0
            )
        }

        val goOutWindow = computeGoOutWindow(startIdx, w, aqi)

        val daySlots = w.daily.time.mapIndexed { i, t ->
            val (_, icon) = codeInfo(w.daily.weatherCode[i], isDay = true)
            DaySlot(
                label = if (i == 0) "Today" else fmtDayOfWeek(t),
                icon = icon,
                min = w.daily.tempMin[i].toInt(),
                max = w.daily.tempMax[i].toInt()
            )
        }

        return WeatherUiState(
            isLoading = false,
            locationLabel = label,
            current = w.current,
            hourly = hourSlots,
            daily = daySlots,
            sunrise = fmtTime(w.daily.sunrise.firstOrNull() ?: ""),
            sunset = fmtTime(w.daily.sunset.firstOrNull() ?: ""),
            aqi = aqi,
            uvIndex = w.daily.uvIndexMax.firstOrNull()?.toInt(),
            goOutWindow = goOutWindow
        )
    }

    private fun buildLabel(name: String, admin1: String?, country: String?): String =
        listOfNotNull(name, admin1, country).take(2).joinToString(", ")

    // ---------- "Best Time to Go Outside" ----------
    // Scores each of the next ~8 hours on temperature comfort, rain chance,
    // and wind, then returns the best window. This is the app's unique
    // differentiator vs. standard weather apps, which show data but don't
    // tell you *when* to actually go outside.
    private fun computeGoOutWindow(startIdx: Int, w: ForecastResponse, aqi: Int?): GoOutWindow? {
        val endIdx = min(startIdx + 8, w.hourly.time.size)
        if (endIdx <= startIdx) return null

        var bestIdx = -1
        var bestScore = -1

        for (i in startIdx until endIdx) {
            val temp = w.hourly.temperature[i]
            val rainChance = w.hourly.precipitationProbability.getOrNull(i) ?: 0
            val code = w.hourly.weatherCode[i]

            var score = 100
            // Comfortable range roughly 18-28°C; penalize outside it
            val tempPenalty = when {
                temp in 18.0..28.0 -> 0
                temp < 18.0 -> ((18.0 - temp) * 3).toInt()
                else -> ((temp - 28.0) * 3).toInt()
            }
            score -= tempPenalty
            score -= rainChance // direct penalty per % chance of rain
            score -= when (code) {
                95, 96, 99 -> 60 // thunderstorm
                61, 63, 65, 80, 81, 82 -> 30 // rain/showers
                71, 73, 75, 85, 86 -> 25 // snow
                45, 48 -> 15 // fog
                else -> 0
            }
            if (aqi != null && aqi > 100) score -= 15

            score = score.coerceIn(0, 100)
            if (score > bestScore) {
                bestScore = score
                bestIdx = i
            }
        }

        if (bestIdx < 0) return null

        val startLabel = fmtHour(w.hourly.time[bestIdx])
        val endLabel = if (bestIdx + 1 < w.hourly.time.size) fmtHour(w.hourly.time[bestIdx + 1]) else startLabel
        val rainChance = w.hourly.precipitationProbability.getOrNull(bestIdx) ?: 0

        val reason = when {
            bestScore >= 80 -> "Comfortable temps, low rain chance"
            rainChance > 30 -> "Best available window, but rain is possible"
            else -> "Best balance of temperature and conditions today"
        }

        return GoOutWindow(
            label = "$startLabel – $endLabel",
            score = bestScore,
            reason = reason
        )
    }
}

fun codeInfo(code: Int, isDay: Boolean): Pair<String, String> = when (code) {
    0 -> "Clear sky" to if (isDay) "☀️" else "🌙"
    1 -> "Mostly clear" to "🌤️"
    2 -> "Partly cloudy" to if (isDay) "⛅" else "☁️"
    3 -> "Overcast" to "☁️"
    45, 48 -> "Foggy" to "🌫️"
    51, 53 -> "Drizzle" to "🌦️"
    55, 56, 57 -> "Freezing drizzle" to "🌧️"
    61 -> "Light rain" to "🌦️"
    63 -> "Rain" to "🌧️"
    65 -> "Heavy rain" to "🌧️"
    66, 67 -> "Freezing rain" to "🌧️"
    71 -> "Light snow" to "🌨️"
    73, 77 -> "Snow" to "❄️"
    75 -> "Heavy snow" to "❄️"
    80 -> "Light showers" to "🌦️"
    81 -> "Showers" to "🌧️"
    82 -> "Violent showers" to "⛈️"
    85, 86 -> "Snow showers" to "🌨️"
    95 -> "Thunderstorm" to "⛈️"
    96, 99 -> "Thunderstorm, hail" to "⛈️"
    else -> "—" to "·"
}

fun aqiInfo(aqi: Int?): Triple<String, String, String> = when {
    aqi == null -> Triple("Unavailable", "#9AA3C7", "Air quality data is not available for this location.")
    aqi <= 50 -> Triple("Good", "#4FD39A", "Air quality is satisfactory and poses little or no risk.")
    aqi <= 100 -> Triple("Moderate", "#F4C76A", "Acceptable, but there may be a moderate risk for sensitive people.")
    aqi <= 150 -> Triple("Unhealthy (sensitive)", "#F2795B", "Sensitive groups may experience health effects.")
    aqi <= 200 -> Triple("Unhealthy", "#F2795B", "Everyone may begin to experience health effects.")
    else -> Triple("Very Unhealthy", "#F2795B", "Health warnings of emergency conditions.")
}

fun windDirectionLabel(degrees: Int): String {
    val directions = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val index = (((degrees % 360) + 360) % 360 / 45.0).let { Math.round(it).toInt() % 8 }
    return "${directions[index]} · $degrees°"
}

fun uvLabel(uv: Int?): String = when {
    uv == null -> ""
    uv < 3 -> "Low"
    uv < 6 -> "Moderate"
    uv < 8 -> "High"
    uv < 11 -> "Very High"
    else -> "Extreme"
}

data class MoonPhase(val fraction: Double, val name: String, val illumination: Int)

fun moonPhase(date: Date = Date()): MoonPhase {
    val synodic = 29.53058867
    val knownNewMoon = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        set(2000, Calendar.JANUARY, 6, 18, 14, 0)
    }.time
    val diffDays = (date.time - knownNewMoon.time) / 86400000.0
    var phase = (diffDays % synodic) / synodic
    if (phase < 0) phase += 1

    val name = when {
        phase < 0.03 -> "New Moon"
        phase < 0.22 -> "Waxing Crescent"
        phase < 0.28 -> "First Quarter"
        phase < 0.47 -> "Waxing Gibbous"
        phase < 0.53 -> "Full Moon"
        phase < 0.72 -> "Waning Gibbous"
        phase < 0.78 -> "Last Quarter"
        phase < 0.97 -> "Waning Crescent"
        else -> "New Moon"
    }
    val illumination = (kotlin.math.max(0.0, 1 - abs(phase - 0.5) * 2) * 100).toInt()
    return MoonPhase(phase, name, illumination)
}

private fun parseIso(iso: String): Date? = runCatching {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault()).parse(iso)
}.getOrNull()

fun fmtTime(iso: String): String {
    val d = parseIso(iso) ?: return "—"
    return SimpleDateFormat("h:mm a", Locale.getDefault()).format(d)
}

fun fmtHour(iso: String): String {
    val d = parseIso(iso) ?: return "—"
    return SimpleDateFormat("h a", Locale.getDefault()).format(d)
}

fun fmtDayOfWeek(iso: String): String {
    val d = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(iso) }.getOrNull() ?: return "—"
    return SimpleDateFormat("EEE", Locale.getDefault()).format(d)
}
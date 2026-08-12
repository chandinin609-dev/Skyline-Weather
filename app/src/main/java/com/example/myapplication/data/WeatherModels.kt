package com.example.myapplication.data

import com.google.gson.annotations.SerializedName

// ---------- Geocoding ----------
data class GeoResponse(
    @SerializedName("results") val results: List<GeoResult>?
)

data class GeoResult(
    @SerializedName("name") val name: String,
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("country") val country: String?,
    @SerializedName("admin1") val admin1: String?
)

// ---------- Forecast ----------
data class ForecastResponse(
    @SerializedName("current") val current: CurrentWeather,
    @SerializedName("hourly") val hourly: HourlyWeather,
    @SerializedName("daily") val daily: DailyWeather
)

data class CurrentWeather(
    @SerializedName("time") val time: String,
    @SerializedName("temperature_2m") val temperature: Double,
    @SerializedName("relative_humidity_2m") val humidity: Int,
    @SerializedName("apparent_temperature") val feelsLike: Double,
    @SerializedName("weather_code") val weatherCode: Int,
    @SerializedName("wind_speed_10m") val windSpeed: Double,
    @SerializedName("wind_direction_10m") val windDirection: Int = 0,
    @SerializedName("pressure_msl") val pressure: Double,
    @SerializedName("is_day") val isDay: Int
)

data class HourlyWeather(
    @SerializedName("time") val time: List<String>,
    @SerializedName("temperature_2m") val temperature: List<Double>,
    @SerializedName("weather_code") val weatherCode: List<Int>,
    @SerializedName("precipitation_probability") val precipitationProbability: List<Int> = emptyList(),
    @SerializedName("is_day") val isDay: List<Int> = emptyList()
)

data class DailyWeather(
    @SerializedName("time") val time: List<String>,
    @SerializedName("weather_code") val weatherCode: List<Int>,
    @SerializedName("temperature_2m_max") val tempMax: List<Double>,
    @SerializedName("temperature_2m_min") val tempMin: List<Double>,
    @SerializedName("sunrise") val sunrise: List<String>,
    @SerializedName("sunset") val sunset: List<String>,
    @SerializedName("uv_index_max") val uvIndexMax: List<Double?>
)

// ---------- Air quality ----------
data class AirQualityResponse(
    @SerializedName("current") val current: AirQualityCurrent?
)

data class AirQualityCurrent(
    @SerializedName("us_aqi") val usAqi: Int?
)

// ---------- UI-ready state ----------
data class WeatherUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val locationLabel: String = "",
    val current: CurrentWeather? = null,
    val hourly: List<HourSlot> = emptyList(),
    val daily: List<DaySlot> = emptyList(),
    val sunrise: String = "",
    val sunset: String = "",
    val aqi: Int? = null,
    val uvIndex: Int? = null,
    val goOutWindow: GoOutWindow? = null
)

data class HourSlot(
    val label: String,
    val temp: Int,
    val icon: String,
    val rainChance: Int = 0
)
data class DaySlot(val label: String, val icon: String, val min: Int, val max: Int)

// ---------- Unique feature: "Best Time to Go Outside" ----------
// Scans the next several hours and scores each one on comfort (temp, rain
// chance, wind, UV, AQI) so the app can tell the user WHEN to go out today,
// not just what the weather is right now. No other mainstream weather app
// surfaces this as a single glanceable answer.
data class GoOutWindow(
    val label: String,      // e.g. "5 PM – 6 PM"
    val score: Int,         // 0-100 comfort score
    val reason: String      // short human-readable explanation
)
package com.example.myapplication.data

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

// --- Retrofit API Interfaces ---

interface GeocodingApiService {
    @GET("v1/search")
    suspend fun search(
        @Query("name") name: String,
        @Query("count") count: Int = 10
    ): GeoResponse
}

interface WeatherApiService {
    @GET("v1/forecast")
    suspend fun getForecast(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("current") current: String = "temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation,weather_code,pressure_msl,wind_speed_10m,wind_direction_10m",
        @Query("hourly") hourly: String = "temperature_2m,weather_code,precipitation_probability,is_day",
        @Query("daily") daily: String = "weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,uv_index_max",
        @Query("timezone") timezone: String = "auto"
    ): ForecastResponse
}

interface AirQualityApiService {
    @GET("v1/air-quality")
    suspend fun getAirQuality(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("current") current: String = "us_aqi"
    ): AirQualityResponse
}

// --- Retrofit Client Singleton ---

object WeatherClient {
    private const val GEO_BASE_URL = "https://geocoding-api.open-meteo.com/"
    private const val WEATHER_BASE_URL = "https://api.open-meteo.com/"
    private const val AIR_QUALITY_BASE_URL = "https://air-quality-api.open-meteo.com/"

    val geocodingApi: GeocodingApiService by lazy {
        Retrofit.Builder()
            .baseUrl(GEO_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GeocodingApiService::class.java)
    }

    val forecastApi: WeatherApiService by lazy {
        Retrofit.Builder()
            .baseUrl(WEATHER_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(WeatherApiService::class.java)
    }

    val airQualityApi: AirQualityApiService by lazy {
        Retrofit.Builder()
            .baseUrl(AIR_QUALITY_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AirQualityApiService::class.java)
    }
}
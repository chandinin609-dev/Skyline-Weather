package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.data.GeoResult
import com.example.myapplication.data.WeatherUiState
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.launch

private val BgTop = Color(0xFF0B1226)
private val CardColor = Color(0xFF1A2340)
private val CardSoft = Color(0xFF161D38)
private val LineColor = Color(0x14FFFFFF)
private val TextColor = Color(0xFFEEF1FF)
private val SubColor = Color(0xFF9AA3C7)
private val Accent = Color(0xFF5EC8FF)

class MainActivity : ComponentActivity() {

    private var pendingLocationCallback: ((Double, Double) -> Unit)? = null
    private var pendingErrorCallback: (() -> Unit)? = null

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val onFused = pendingLocationCallback
        val onError = pendingErrorCallback
        pendingLocationCallback = null
        pendingErrorCallback = null

        if (granted && onFused != null) {
            requestFreshLocation(onFused, onError)
        } else {
            // Permission denied — used to just silently do nothing here,
            // which is why the splash screen looked "stuck". Now we report
            // the failure so the UI can show a message and let the user retry
            // or search manually.
            onError?.invoke()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val weatherViewModel: WeatherViewModel = viewModel()
            SkylineTheme {
                WeatherScreen(
                    viewModel = weatherViewModel,
                    onRequestLocation = { onFused, onError ->
                        checkAndRequestLocation(onFused, onError)
                    }
                )
            }
        }
    }

    private fun checkAndRequestLocation(onFused: (Double, Double) -> Unit, onError: () -> Unit) {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val servicesEnabled = try {
            locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                    locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        } catch (e: Exception) {
            false
        }

        if (!servicesEnabled) {
            // Previously the app would just wait forever if location was
            // toggled off on the device — lastLocation returns null and no
            // update ever arrives. Fail fast instead.
            onError()
            return
        }

        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineGranted || coarseGranted) {
            requestFreshLocation(onFused, onError)
        } else {
            pendingLocationCallback = onFused
            pendingErrorCallback = onError
            try {
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            } catch (e: Exception) {
                e.printStackTrace()
                onError()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestFreshLocation(onFused: (Double, Double) -> Unit, onError: (() -> Unit)?) {
        val handler = Handler(Looper.getMainLooper())
        var completed = false

        // Hard timeout: previously there was no limit at all, so a weak GPS
        // signal (e.g. indoors) left the splash screen spinning forever.
        val timeoutRunnable = Runnable {
            if (!completed) {
                completed = true
                onError?.invoke()
            }
        }

        try {
            val client = LocationServices.getFusedLocationProviderClient(this)
            handler.postDelayed(timeoutRunnable, 15_000L)

            client.lastLocation.addOnSuccessListener { lastLocation ->
                if (completed) return@addOnSuccessListener
                if (lastLocation != null) {
                    completed = true
                    handler.removeCallbacks(timeoutRunnable)
                    onFused(lastLocation.latitude, lastLocation.longitude)
                } else {
                    val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 0L)
                        .setWaitForAccurateLocation(false)
                        .setMaxUpdates(1)
                        .build()

                    val callback = object : LocationCallback() {
                        override fun onLocationResult(result: LocationResult) {
                            try {
                                client.removeLocationUpdates(this)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            if (completed) return
                            val location = result.lastLocation
                            if (location != null) {
                                completed = true
                                handler.removeCallbacks(timeoutRunnable)
                                onFused(location.latitude, location.longitude)
                            } else {
                                completed = true
                                handler.removeCallbacks(timeoutRunnable)
                                onError?.invoke()
                            }
                        }
                    }

                    try {
                        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                    } catch (e: Exception) {
                        e.printStackTrace()
                        if (!completed) {
                            completed = true
                            handler.removeCallbacks(timeoutRunnable)
                            onError?.invoke()
                        }
                    }
                }
            }.addOnFailureListener {
                it.printStackTrace()
                if (!completed) {
                    completed = true
                    handler.removeCallbacks(timeoutRunnable)
                    onError?.invoke()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            if (!completed) {
                completed = true
                handler.removeCallbacks(timeoutRunnable)
                onError?.invoke()
            }
        }
    }
}

@Composable
fun SkylineTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = BgTop,
            surface = CardColor,
            primary = Accent,
            onBackground = TextColor,
            onSurface = TextColor
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeatherScreen(
    viewModel: WeatherViewModel,
    onRequestLocation: ((Double, Double) -> Unit, () -> Unit) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    var query by remember { mutableStateOf("") }

    val savedCities = remember { mutableStateListOf<String>() }
    var showSavedCitiesDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    var isFahrenheit by remember { mutableStateOf(false) }
    var isDarkMode by remember { mutableStateOf(true) }
    var notificationsEnabled by remember { mutableStateOf(true) }

    var locationInitialized by remember { mutableStateOf(false) }
    var isLocating by remember { mutableStateOf(false) }
    var locationError by remember { mutableStateOf<String?>(null) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    fun requestLocation() {
        isLocating = true
        locationError = null
        onRequestLocation(
            { lat, lon ->
                isLocating = false
                viewModel.loadByCoordinates(context, lat, lon)
                locationInitialized = true
            },
            {
                isLocating = false
                locationError = "Couldn't get your location. Make sure location access is allowed " +
                        "and location services are turned on, or search for a city instead."
            }
        )
    }

    LaunchedEffect(Unit) {
        requestLocation()
    }

    if (!locationInitialized) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BgTop),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(CardColor)
                        .border(1.dp, LineColor, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🌤️", fontSize = 36.sp)
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "Skyline Weather",
                    color = TextColor,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (isLocating)
                        "Finding your location..."
                    else
                        "Please choose your location preference to load your local forecast.",
                    color = SubColor,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                if (locationError != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = locationError ?: "",
                        color = Color(0xFFF2795B),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(Modifier.height(24.dp))
                if (isLocating) {
                    CircularProgressIndicator(color = Accent)
                } else {
                    Button(
                        onClick = { requestLocation() },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) {
                        Text(
                            if (locationError != null) "Try Again" else "Grant Location Access",
                            color = BgTop,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { locationInitialized = true }) {
                    Text("Skip for now — I'll search manually", color = SubColor)
                }
            }
        }
        return
    }

    if (showSavedCitiesDialog) {
        AlertDialog(
            onDismissRequest = { showSavedCitiesDialog = false },
            containerColor = CardColor,
            title = { Text("Saved Cities", color = TextColor, fontWeight = FontWeight.Bold) },
            text = {
                if (savedCities.isEmpty()) {
                    Text("No saved cities yet. Search a city and tap the heart icon in the top right to save it here.", color = SubColor, fontSize = 14.sp)
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        savedCities.forEach { cityName ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showSavedCitiesDialog = false
                                        query = cityName
                                        viewModel.searchAndLoad(cityName)
                                    }
                                    .padding(vertical = 12.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.LocationOn, contentDescription = null, tint = Accent, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(cityName, color = TextColor, fontSize = 15.sp)
                                }
                                IconButton(
                                    onClick = { savedCities.remove(cityName) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = SubColor, modifier = Modifier.size(16.dp))
                                }
                            }
                            HorizontalDivider(color = LineColor, thickness = 0.5.dp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSavedCitiesDialog = false }) {
                    Text("Close", color = Accent)
                }
            }
        )
    }

    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            containerColor = CardColor,
            title = { Text("Settings", color = TextColor, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Temperature Unit", color = TextColor, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                            Text(if (isFahrenheit) "Currently Fahrenheit (°F)" else "Currently Celsius (°C)", color = SubColor, fontSize = 12.sp)
                        }
                        Switch(
                            checked = isFahrenheit,
                            onCheckedChange = { isFahrenheit = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Accent.copy(alpha = 0.3f))
                        )
                    }
                    HorizontalDivider(color = LineColor, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Dark Theme", color = TextColor, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                            Text("Use dark background aesthetic", color = SubColor, fontSize = 12.sp)
                        }
                        Switch(
                            checked = isDarkMode,
                            onCheckedChange = { isDarkMode = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Accent.copy(alpha = 0.3f))
                        )
                    }
                    HorizontalDivider(color = LineColor, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Weather Alerts", color = TextColor, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                            Text("Receive severe weather updates", color = SubColor, fontSize = 12.sp)
                        }
                        Switch(
                            checked = notificationsEnabled,
                            onCheckedChange = { notificationsEnabled = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Accent.copy(alpha = 0.3f))
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Save & Close", color = Accent)
                }
            }
        )
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = CardColor,
                drawerContentColor = TextColor
            ) {
                Spacer(Modifier.height(32.dp))
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Accent.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🌤️", fontSize = 20.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Skyline Weather", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextColor)
                        Text("v1.0 Pro", fontSize = 12.sp, color = SubColor)
                    }
                }
                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = LineColor)
                Spacer(Modifier.height(8.dp))
                NavigationDrawerItem(
                    label = { Text("Home", color = TextColor, fontWeight = FontWeight.Medium) },
                    selected = true,
                    onClick = {
                        scope.launch {
                            drawerState.close()
                            query = ""
                            viewModel.clearSuggestions()
                        }
                    },
                    colors = NavigationDrawerItemDefaults.colors(selectedContainerColor = CardSoft)
                )
                NavigationDrawerItem(
                    label = { Text("Saved Cities (${savedCities.size})", color = TextColor, fontWeight = FontWeight.Medium) },
                    selected = false,
                    onClick = {
                        scope.launch {
                            drawerState.close()
                            showSavedCitiesDialog = true
                        }
                    }
                )
                NavigationDrawerItem(
                    label = { Text("Settings", color = TextColor, fontWeight = FontWeight.Medium) },
                    selected = false,
                    onClick = {
                        scope.launch {
                            drawerState.close()
                            showSettingsDialog = true
                        }
                    }
                )
            }
        }
    ) {
        val currentLocLabel = uiState.locationLabel
        val isCurrentSaved = savedCities.contains(currentLocLabel)

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🌤️", fontSize = 18.sp)
                            Spacer(Modifier.width(8.dp))
                            Text("Skyline Weather", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = TextColor)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Open Menu", tint = TextColor)
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                if (currentLocLabel.isNotBlank()) {
                                    if (savedCities.contains(currentLocLabel)) {
                                        savedCities.remove(currentLocLabel)
                                    } else {
                                        savedCities.add(currentLocLabel)
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (isCurrentSaved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = "Save City",
                                tint = if (isCurrentSaved) Color(0xFFFF5252) else TextColor
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BgTop)
            ) {
                // Dynamic Weather Background Image Layer using your images (`sun`, `rain`, `storm`, `moon`)
                val bgIsDay = (uiState.current?.isDay ?: 1) == 1
                val bgRes = getWeatherBackgroundRes(uiState.current?.weatherCode, bgIsDay)
                if (bgRes != 0) {
                    Image(
                        painter = painterResource(id = bgRes),
                        contentDescription = "Weather Background",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        alpha = 0.5f // Set high enough to see clearly
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 16.dp)
                        .verticalScrollFix(),
                ) {
                    Spacer(Modifier.height(12.dp))

                    SearchBar(
                        query = query,
                        onQueryChange = { newQuery ->
                            query = newQuery
                            viewModel.onQueryChanged(newQuery)
                        },
                        onSearch = {
                            if (query.isNotBlank()) {
                                viewModel.searchAndLoad(query)
                                viewModel.clearSuggestions()
                            }
                        },
                        suggestions = suggestions,
                        onPick = { place ->
                            query = place.name
                            viewModel.clearSuggestions()
                            viewModel.selectPlace(place)
                        },
                        onClear = {
                            query = ""
                            viewModel.clearSuggestions()
                        }
                    )

                    Spacer(Modifier.height(16.dp))

                    when {
                        uiState.isLoading -> LoadingMessage("Loading...")
                        uiState.error != null -> LoadingMessage(uiState.error ?: "Something went wrong.")
                        uiState.current == null -> WelcomeHeroPlaceholder()
                        else -> WeatherContent(
                            state = uiState,
                            isFahrenheit = isFahrenheit
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
fun WelcomeHeroPlaceholder() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(CardColor.copy(alpha = 0.85f))
                .border(1.dp, LineColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("🌍", fontSize = 36.sp)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Welcome to Skyline Weather",
            color = TextColor,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Search a city above or tap your current location to view accurate live forecasts.",
            color = SubColor,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
            lineHeight = 20.sp
        )
    }
}

@Composable
private fun Modifier.verticalScrollFix(): Modifier {
    val scrollState = rememberScrollState()
    return this.then(Modifier.verticalScroll(scrollState))
}

@Composable
private fun Modifier.clickableText(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable { onClick() })

@Composable
fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    suggestions: List<GeoResult>,
    onPick: (GeoResult) -> Unit,
    onClear: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CardColor.copy(alpha = 0.85f))
                .border(1.dp, LineColor, RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            TextField(
                value = query,
                onValueChange = { newText: String ->
                    onQueryChange(newText)
                },
                placeholder = { Text("Search for a city (e.g. Hyderabad)...", color = SubColor) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = TextColor,
                    unfocusedTextColor = TextColor
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    onSearch()
                }),
                modifier = Modifier.weight(1f)
            )

            if (query.isNotEmpty()) {
                IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear", tint = SubColor, modifier = Modifier.size(16.dp))
                }
            }
        }

        if (suggestions.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(CardColor.copy(alpha = 0.95f))
                    .border(1.dp, LineColor, RoundedCornerShape(14.dp))
            ) {
                suggestions.forEach { r ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickableText { onPick(r) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "${r.name}${r.admin1?.let { ", $it" } ?: ""}",
                            color = TextColor,
                            fontSize = 14.sp
                        )
                        Text(r.country ?: "", color = SubColor, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun LoadingMessage(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 60.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = SubColor, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun WeatherContent(
    state: WeatherUiState,
    isFahrenheit: Boolean
) {
    val cur = state.current ?: return
    val codeInfoResult = codeInfo(cur.weatherCode, cur.isDay == 1)
    val desc = codeInfoResult.first
    val icon = codeInfoResult.second

    val today = state.daily.firstOrNull()

    val aqiResult = aqiInfo(state.aqi)
    val aqLabel = aqiResult.first
    val aqColorHex = aqiResult.second
    val aqDesc = aqiResult.third

    fun convertTemp(celsius: Double): Int {
        return if (isFahrenheit) ((celsius * 9 / 5) + 32).toInt() else celsius.toInt()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = state.locationLabel,
            color = TextColor,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = "Updated ${fmtTime(cur.time)}",
            color = SubColor,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp),
            textAlign = TextAlign.Center
        )
        Text(
            text = "${convertTemp(cur.temperature)}°",
            color = TextColor,
            fontSize = 80.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(vertical = 4.dp),
            textAlign = TextAlign.Center
        )
        Text(
            text = "$icon $desc",
            color = TextColor,
            fontSize = 16.sp,
            textAlign = TextAlign.Center
        )
        if (today != null) {
            Text(
                text = "Feels like ${convertTemp(cur.feelsLike)}° · H:${convertTemp(today.max.toDouble())}° L:${convertTemp(today.min.toDouble())}°",
                color = SubColor,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp),
                textAlign = TextAlign.Center
            )
        }
    }

    state.goOutWindow?.let { window ->
        Card1(title = "Best time to go outside today") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(window.label, color = TextColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(window.reason, color = SubColor, fontSize = 13.sp)
                }
                Spacer(Modifier.width(16.dp))
                val scoreColor = when {
                    window.score >= 75 -> Color(0xFF4FD39A)
                    window.score >= 50 -> Color(0xFFF4C76A)
                    else -> Color(0xFFF2795B)
                }
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(scoreColor.copy(alpha = 0.15f))
                        .border(1.5.dp, scoreColor, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("${window.score}", color = scoreColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    Card1(title = "Hourly forecast") {
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            state.hourly.forEach { h ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(48.dp)) {
                    Text(h.label, color = SubColor, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(h.icon, fontSize = 20.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("${convertTemp(h.temp.toDouble())}°", color = TextColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (h.rainChance > 0) {
                        Text("${h.rainChance}%", color = Accent, fontSize = 10.sp)
                    }
                }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(modifier = Modifier.weight(1f)) {
            SmallCard(
                title = "Wind",
                value = "${cur.windSpeed.toInt()} km/h",
                subtitle = windDirectionLabel(cur.windDirection)
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            SmallCard(
                title = "Humidity",
                value = "${cur.humidity}%",
                subtitle = if (cur.humidity > 60) "Humid" else "Comfortable"
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(modifier = Modifier.weight(1f)) {
            SmallCard(
                title = "Pressure",
                value = "${cur.pressure.toInt()} hPa",
                subtitle = "Surface pressure"
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            SmallCard(
                title = "UV Index",
                value = "${state.uvIndex ?: 0}",
                subtitle = uvLabel(state.uvIndex)
            )
        }
    }

    Card1(title = "7-day forecast") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.daily.forEach { d ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(d.label, color = TextColor, fontSize = 14.sp, modifier = Modifier.width(64.dp))
                    Text(d.icon, fontSize = 20.sp)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${convertTemp(d.min.toDouble())}°", color = SubColor, fontSize = 14.sp)
                        Box(
                            modifier = Modifier
                                .width(60.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Accent.copy(alpha = 0.3f))
                        )
                        Text("${convertTemp(d.max.toDouble())}°", color = TextColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    Card1(title = "Air quality") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(aqLabel, color = Color(aqColorHex.toColorInt()), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(aqDesc, color = SubColor, fontSize = 13.sp)
            }
            Spacer(Modifier.width(16.dp))
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(Color(aqColorHex.toColorInt()).copy(alpha = 0.15f))
                    .border(1.5.dp, Color(aqColorHex.toColorInt()), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("${state.aqi ?: "—"}", color = Color(aqColorHex.toColorInt()), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(modifier = Modifier.weight(1f)) {
            SmallCard(
                title = "Sunrise",
                value = state.sunrise,
                subtitle = "Dawn"
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            SmallCard(
                title = "Sunset",
                value = state.sunset,
                subtitle = "Dusk"
            )
        }
    }

    val moon = moonPhase()
    Card1(title = "Moon phase") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(moon.name, color = TextColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("Illumination: ${moon.illumination}%", color = SubColor, fontSize = 13.sp)
            }
            Text(
                text = when (moon.name) {
                    "New Moon" -> "🌑"
                    "Waxing Crescent" -> "🌒"
                    "First Quarter" -> "🌓"
                    "Waxing Gibbous" -> "🌔"
                    "Full Moon" -> "🌕"
                    "Waning Gibbous" -> "🌖"
                    "Last Quarter" -> "🌗"
                    else -> "🌘"
                },
                fontSize = 32.sp
            )
        }
    }
}

@Composable
fun Card1(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(CardColor.copy(alpha = 0.85f))
            .border(1.dp, LineColor, RoundedCornerShape(20.dp))
            .padding(18.dp)
    ) {
        Text(title, color = SubColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 14.dp))
        content()
    }
}

@Composable
fun SmallCard(title: String, value: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CardColor.copy(alpha = 0.85f))
            .border(1.dp, LineColor, RoundedCornerShape(20.dp))
            .padding(18.dp)
    ) {
        Text(title, color = SubColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(value, color = TextColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = SubColor, fontSize = 11.5.sp)
    }
}

// Linked directly to your files: sun.jpg, moon.jpg, rain.jpg, storm.jpg
//
// FIX: this used to map codes 1/2/3 (mostly clear / partly cloudy / overcast)
// straight to the moon image, regardless of whether it was actually day or
// night — so a cloudy afternoon would show a moon background. Day/night is
// now driven by the real `is_day` flag from the API.
//
// NOTE: there's no dedicated snow/fog asset yet, so those codes currently
// fall back to rain (snow) or sun/moon (fog). Add snow.jpg / fog.jpg
// drawables and extend this function for full accuracy.
fun getWeatherBackgroundRes(code: Int?, isDay: Boolean): Int {
    return when (code) {
        0, 1, 2, 3 -> if (isDay) R.drawable.sun else R.drawable.moon
        45, 48 -> if (isDay) R.drawable.sun else R.drawable.moon // fog — TODO add fog.jpg
        51, 53, 55, 56, 57,
        61, 63, 65, 66, 67,
        80, 81, 82 -> R.drawable.rain
        71, 73, 75, 77, 85, 86 -> R.drawable.rain // snow — TODO add snow.jpg
        95, 96, 99 -> R.drawable.storm
        else -> if (isDay) R.drawable.sun else R.drawable.moon
    }
}

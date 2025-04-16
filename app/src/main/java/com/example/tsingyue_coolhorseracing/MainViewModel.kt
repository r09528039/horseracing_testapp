package com.example.tsingyue_coolhorseracing

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.tsingyue_coolhorseracing.database.AppDatabase
import com.example.tsingyue_coolhorseracing.model.BettingRecord
import com.example.tsingyue_coolhorseracing.model.Horse
import com.example.tsingyue_coolhorseracing.network.CurrencyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

class MainViewModel(application: Application) : AndroidViewModel(application) {
    // Database access
    private val database = AppDatabase.getDatabase(application)
    private val bettingRecordDao = database.bettingRecordDao()

    // LiveData for betting enabled state
    private val _bettingEnabled = MutableLiveData(true)
    val bettingEnabled: LiveData<Boolean> = _bettingEnabled

    // Repository for currency exchange
    private val currencyRepository = CurrencyRepository()

    // LiveData for UI updates
    private val _balance = MutableLiveData(10000) // Initial balance in TWD
    val balance: LiveData<Int> = _balance

    private val _exchangeRate = MutableLiveData(30.0f) // Default USD to TWD rate
    val exchangeRate: LiveData<Float> = _exchangeRate

    private val _horses = MutableLiveData<List<Horse>>()
    val horses: LiveData<List<Horse>> = _horses

    private val _isRaceRunning = MutableLiveData(false)
    val isRaceRunning: LiveData<Boolean> = _isRaceRunning

    private val _horsePositions = MutableLiveData<List<Int>>()
    val horsePositions: LiveData<List<Int>> = _horsePositions

    private val _winningHorse = MutableLiveData<Int?>(null)
    val winningHorse: LiveData<Int?> = _winningHorse

    private val _betHorseIndex = MutableLiveData<Int?>(null)
    val betHorseIndex: LiveData<Int?> = _betHorseIndex

    private val _betAmount = MutableLiveData(0)
    val betAmount: LiveData<Int> = _betAmount

    private val _currentBetInfo = MutableLiveData<String>("Current Bet: None")
    val currentBetInfo: LiveData<String> = _currentBetInfo

    // History records
    val bettingHistory: LiveData<List<BettingRecord>> = bettingRecordDao.getAllRecords()

    init {
        // Initialize horses with default odds
        _horses.value = List(4) { index ->
            Horse(id = index, odds = 2.0)
        }

        // Initialize horse positions
        _horsePositions.value = List(4) { 0 }

        // Fetch current exchange rate
        refreshExchangeRate()
    }

    fun refreshExchangeRate() {
        viewModelScope.launch {
            try {
                val rate = currencyRepository.getUsdToTwdRate()
                // printing the rate for debugging
                Log.d("MainViewModel", "Fetched exchange rate: $rate")
                _exchangeRate.value = rate.toFloat()
            } catch (e: Exception) {
                Log.d("MainViewModel", "Error fetching exchange rate: ${e.message}")
            }
        }
    }

    fun placeBet(horseIndex: Int, usdAmount: Int, twdAmount: Int) {
        if (_isRaceRunning.value == true || _betHorseIndex.value != null) return
        if (twdAmount > _balance.value ?: 0) return
        if (usdAmount <= 0) return

        _betHorseIndex.value = horseIndex
        _betAmount.value = usdAmount
        _balance.value = (_balance.value ?: 10000) - twdAmount

        _currentBetInfo.value = "Current Bet: $usdAmount USD on Horse ${horseIndex + 1}"
        _bettingEnabled.value = false // Disable betting after placing a bet
    }

    fun resetBet() {
        _betHorseIndex.value = null
        _betAmount.value = 0
        _currentBetInfo.value = "Current Bet: None"
        _bettingEnabled.value = true // Re-enable betting for the next race
    }

    fun startRace() {
        if (_isRaceRunning.value == true) return

        _isRaceRunning.value = true
        _winningHorse.value = null
        _horsePositions.value = List(4) { 0 }

        // Reset positions before race starts
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val winnerLock = Object()
        var raceFinished = false

        // Create threads for each horse
        val threads = ArrayList<Thread>(4)

        for (horseIndex in 0 until 4) {
            val thread = Thread {
                val random = java.util.Random()
                var position = 0
                var shouldContinueRace = true

                while (position < 100 && shouldContinueRace) {
                    // Check if race is already finished
                    synchronized(winnerLock) {
                        if (raceFinished) {
                            shouldContinueRace = false
                        }
                    }

                    if (!shouldContinueRace) break

                    // Random speed for each horse
                    val speed = random.nextInt(5) + 1
                    position += speed
                    if (position > 100) position = 100

                    // Update UI on main thread
                    mainHandler.post {
                        val currentPositions = _horsePositions.value?.toMutableList() ?: mutableListOf(0, 0, 0, 0)
                        currentPositions[horseIndex] = position
                        _horsePositions.value = currentPositions
                    }

                    // Check if this horse won
                    if (position >= 100) {
                        synchronized(winnerLock) {
                            if (!raceFinished) {
                                raceFinished = true
                                mainHandler.post {
                                    _winningHorse.value = horseIndex
                                    processRaceResult(horseIndex)
                                    _isRaceRunning.value = false
                                }
                            }
                        }
                        break
                    }

                    // Pause between movements
                    try {
                        Thread.sleep(100)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }

            threads.add(thread)
            thread.start()
        }

        // Monitor thread to ensure race completion
        Thread {
            // Give the race some time to finish naturally
            try {
                Thread.sleep(15000) // 15 seconds timeout
            } catch (e: InterruptedException) {
                // Ignore interruption
            }

            synchronized(winnerLock) {
                if (!raceFinished) {
                    raceFinished = true
                    // If no winner was determined, pick one based on current positions
                    mainHandler.post {
                        if (_winningHorse.value == null) {
                            val positions = _horsePositions.value ?: List(4) { 0 }
                            val maxPosition = positions.maxOrNull() ?: 0
                            val winnerIndices = positions.indices.filter { positions[it] == maxPosition }
                            val winnerIndex = winnerIndices.random()
                            _winningHorse.value = winnerIndex
                            processRaceResult(winnerIndex)
                        }
                        _isRaceRunning.value = false
                    }
                }
            }

            // Interrupt all threads to clean up
            for (thread in threads) {
                if (thread.isAlive) {
                    thread.interrupt()
                }
            }
        }.start()
    }

    private fun processRaceResult(winnerIndex: Int) {
        val currentHorses = _horses.value?.toMutableList() ?: return
        val betHorseIndex = _betHorseIndex.value
        val betAmount = _betAmount.value ?: 0
        val exchangeRate = _exchangeRate.value ?: 30.0f

        // Calculate bet amount in TWD
        val betAmountTwd = (betAmount * exchangeRate).toInt()

        // Calculate profit if bet horse won
        var profit = 0
        var newBalance = _balance.value ?: 10000

        if (betHorseIndex != null && betHorseIndex == winnerIndex) {
            val odds = currentHorses[betHorseIndex].odds
            profit = (betAmountTwd * odds).toInt()
            newBalance += profit
        }

        // Update horse odds based on race result
        currentHorses.forEachIndexed { index, horse ->
            if (index == winnerIndex) {
                horse.odds = (horse.odds - 0.1).coerceAtLeast(2.0)
                horse.wins++
            } else {
                horse.odds = (horse.odds + 0.1).coerceAtMost(5.0)
                horse.losses++
            }
        }

        // Update the LiveData
        _horses.value = currentHorses
        _balance.value = newBalance

        // Save betting record to database if a bet was placed
        if (betHorseIndex != null) {
            val record = BettingRecord(
                betAmount = betAmount.toDouble(),
                betAmountInTWD = betAmountTwd.toDouble(),
                horseNumber = betHorseIndex + 1,
                winnerHorseNumber = winnerIndex + 1,
                winAmount = profit.toDouble(),
                balanceAfterBet = newBalance.toDouble(),
                timestamp = Date()
            )

            viewModelScope.launch {
                bettingRecordDao.insert(record)
            }
        }

        // Reset bet
        _betHorseIndex.value = null
        _betAmount.value = 0

        // Refresh exchange rate
        refreshExchangeRate()
        resetBet()
    }

    fun deleteBettingRecord(record: BettingRecord) {
        viewModelScope.launch {
            bettingRecordDao.delete(record)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            bettingRecordDao.deleteAll()
        }
    }

    private suspend fun delay(timeMillis: Long) {
        withContext(Dispatchers.IO) {
            Thread.sleep(timeMillis)
        }
    }
}

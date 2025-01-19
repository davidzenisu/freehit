package com.zenisu.freehit

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.types.Track
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import com.squareup.moshi.JsonAdapter

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import com.squareup.moshi.Moshi
import com.squareup.moshi.JsonClass
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Response
import java.io.IOException


// https://github.com/spotify/android-sdk/issues/322

class MainActivity : AppCompatActivity() {
    private val clientId = "653361071f2c4916a80b0362510a88b8"
    private val redirectUri = "https://com.zenisu.hitfree/callback"
    private val songListUri = "https://raw.githubusercontent.com/davidzenisu/freehit/main/data/spotify_songs.json"
    private val REQUEST_CODE: Int = 1337
    private var spotifyAppRemote: SpotifyAppRemote? = null
    private var songList: SongList? = null
    private var spotifyPremium: Boolean = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d("MainActivity", "Application has started.")
    }

    override fun onPause() {
        super.onPause()
        spotifyAppRemote?.playerApi?.pause()
        Log.d("MainActivity", "Application has paused.")
    }

    fun login(view: View) {
        val builder: AuthorizationRequest.Builder = AuthorizationRequest.Builder(clientId, AuthorizationResponse.Type.TOKEN, redirectUri)
        builder.setScopes(arrayOf("app-remote-control"))
        val request: AuthorizationRequest = builder.build()
        AuthorizationClient.openLoginActivity(this, REQUEST_CODE, request)
    }

    fun playRandomSong(view: View) {
        songList?.songList?.let { songs ->
            val randomSong = songs.random()
            Log.d("MainActivity", "Next random song is: $randomSong")
            // Legacy: Play a playlist
            //val playUri = "spotify:playlist:37i9dQZF1DX2sUQwD7tbmL"
            val playUri = "spotify:track:${randomSong.spotifyId}"
            spotifyAppRemote?.let { spotifyPlayer ->
                spotifyPlayer.playerApi.play(playUri)
                // Subscribe to PlayerState
                spotifyPlayer.playerApi.subscribeToPlayerState().setEventCallback {
                    val track: Track = it.track
                    Log.d("MainActivity", track.name + " by " + track.artist.name)
                }
                return
            }
        }
        Log.d("MainActivity", "Preparations not completed!")
    }

    private fun connect(token: String) {
        Log.d("MainActivity", "Connecting to spotify now...")
        val connectionParams = ConnectionParams.Builder(clientId)
            .setRedirectUri(redirectUri)
            .showAuthView(false)
            .build()

        SpotifyAppRemote.connect(this, connectionParams, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                spotifyAppRemote = appRemote
                Log.d("MainActivity", "Connected! Yay!")
                // Now you can start interacting with App Remote
                connected()
            }

            override fun onFailure(throwable: Throwable) {
                Log.e("MainActivity", throwable.message, throwable)
                // Something went wrong when attempting to connect! Handle errors here
            }
        })
    }

    private fun connected() {
        premiumCheck()
    }

    private fun premiumCheck() {
        spotifyAppRemote?.let { spotifyPlayer ->
            spotifyPlayer.userApi.capabilities.setResultCallback { capabilities ->
                if (capabilities.canPlayOnDemand) {
                    // Next, fetch song list
                    fetchSongList()
                } else {
                    Log.d("MainActivity", "User cannot play on demand!")
                }
            }
        }
    }

    private fun fetchSongList() {
        val moshi = Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
        val adapter = moshi.adapter(SongList::class.java)
        fetchJsonData(songListUri, adapter, ::setSongList)
    }

    private fun setSongList(songData: SongList?) {
        songList = songData
    }

    @Deprecated("This method has been deprecated")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d("MainActivity", "Activity has completed with code $requestCode")

        if (requestCode == REQUEST_CODE) {
            Log.d("MainActivity", "Spotify login has completed")
            val response : AuthorizationResponse = AuthorizationClient.getResponse(resultCode, data)

            when (response.type) {
                AuthorizationResponse.Type.TOKEN -> {
                    Log.d("MainActivity", "Successful login to Spotify")
                    connect(response.accessToken)
                }
                AuthorizationResponse.Type.ERROR -> Log.d("MainActivity", "Error when logging in to Spofity")
                else -> { // Note the block
                    Log.d("MainActivity", "Some weird issue")
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        spotifyAppRemote?.let {
            SpotifyAppRemote.disconnect(it)
        }
    }

    private fun <T> fetchJsonData(url: String, adapter: JsonAdapter<T>, callback: (T?) -> Unit) {
        try {
            // Create OkHttp client
            val client = OkHttpClient()

            // Build request
            val request = Request.Builder()
                .url(url)
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    e.printStackTrace()
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful) throw IOException("Unexpected code $response")

                        response.body?.let { responseBody ->
                            val jsonData = responseBody.string()
                            val apiResponse = adapter.fromJson(jsonData)

                            // Use the extracted data
                            Log.d("MainActivity", "Parsed Data: $apiResponse")
                            callback(apiResponse)
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.e("MainActivity", "Error fetching data", e)
        }
    }
}

// Define the data model for JSON parsing
@JsonClass(generateAdapter = true)
data class SongList(
    val version: String,
    val songList: List<Song>
)

@JsonClass(generateAdapter = true)
data class Song(
    val title: String,
    val artist: String,
    val releaseYear: Int,
    val spotifyId: String
)
package com.example.animehelper

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONException
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient()
    private val apiUrl = "https://shikimori.one/api/graphql"
    private val mediaType = "application/json".toMediaType()
    private val selectedFilters = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val searchField: EditText = findViewById(R.id.searchField)
        val searchButton: Button = findViewById(R.id.searchButton)
        val filtersButton: Button = findViewById(R.id.filtersButton)
        val resultText: TextView = findViewById(R.id.resultText)

        searchButton.setOnClickListener {
            val query = searchField.text.toString()
            if (query.isNotBlank()) {
                searchButton.isEnabled = false
                fetchAnimeData(query, selectedFilters.toList()) { result ->
                    runOnUiThread {
                        searchButton.isEnabled = true
                        resultText.text = result
                    }
                }
            }
        }

        filtersButton.setOnClickListener {
            val filterOptions = arrayOf("Movie", "OVA", "TV", "Special")
            val checkedItems = BooleanArray(filterOptions.size) { index ->
                selectedFilters.contains(filterOptions[index].lowercase())
            }

            AlertDialog.Builder(this).apply {
                setTitle("Select Filters")
                setMultiChoiceItems(filterOptions, checkedItems) { _, which, isChecked ->
                    val filter = filterOptions[which].lowercase()
                    if (isChecked) {
                        selectedFilters.add(filter)
                    } else {
                        selectedFilters.remove(filter)
                    }
                }
                setPositiveButton("Apply") { dialog, _ ->
                    dialog.dismiss()
                }
                setNegativeButton("Cancel") { dialog, _ ->
                    dialog.dismiss()
                }
            }.show()
        }
    }

    private fun fetchAnimeData(searchQuery: String, selectedTypes: List<String>, callback: (String) -> Unit) {
        val query = """
            {
              animes(search: "$searchQuery", limit: 1, kind: "movie,ova,tv") {
                id
                name
                russian
                english
                synonyms
                status
                episodes
                duration
                fandubbers
                related {
                  anime {
                    name
                  }
                }
              }
            }
        """.trimIndent()

        val requestBody = RequestBody.create(mediaType, JSONObject().put("query", query).toString())
        val request = Request.Builder()
            .url(apiUrl)
            .post(requestBody)
            .build()

        CoroutineScope(Dispatchers.IO).launch {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val responseData = response.body?.string()
                if ( responseData != null) {
                    try {
                    val jsonObject = JSONObject(responseData)
                    val animeData = jsonObject.getJSONObject("data")
                        .getJSONArray("animes")
                        .getJSONObject(0)
                    //TODO:status processing
                    val name = animeData.optString("name", "N/A")
                    val russian = animeData.optString("russian", "N/A")
                    val english = animeData.optString("english", "N/A")
                    val synonyms = animeData.optJSONArray("synonyms")?.join(", ") ?: "N/A"
                    val episodes = animeData.optInt("episodes", 0)
                    val duration = animeData.optInt("duration", 0)
                    val fandubbers = animeData.optJSONArray("fandubbers")?.let { jsonArray ->
                        List(jsonArray.length()) { index -> jsonArray.optString(index) }
                    } ?: emptyList<String>()

                    val relatedAnimes = animeData.optJSONArray("related")?.let { jsonArray ->
                        mutableListOf<String>().apply {

                            for (i in 0 until jsonArray.length()) {
                                val relatedAnime = jsonArray.getJSONObject(i).optJSONObject("anime")
                                if (relatedAnime != null) {
                                    val animeName = relatedAnime.optString("name", "Без имени")
                                    val relatedInfo = async {
                                        fetchRelatedAnimeData(animeName, selectedTypes)
                                    }
                                    if (relatedInfo.await() != "")
                                    {
                                        add(relatedInfo.await())
                                    }
                                }
                            }
                        }
                    } ?: emptyList()

                    val viewingTime = (episodes * duration).toDouble() / 60
                    val formattedViewingTime = String.format("%.1f", viewingTime)

                    val fandubberSupport = if (fandubbers.contains("AniLibria")) {
                        "AniLibria"
                    } else {
                        "Nah AniLibria"
                    }


                    val result = buildString {
                        append("""
                            JP: $name
                            RU: $russian
                            EN: $english
                            Synonyms: $synonyms

                            Ep_amount: $episodes
                            Duration: $duration minutes
                            Approx: $formattedViewingTime hours ~~

                            $fandubberSupport

                            Related:
                        """.trimIndent())

                        relatedAnimes.forEach {
                            append("\n$it")
                        }
                    }

                    callback(result)
                    } catch (e: JSONException) {
                        callback("Not found")
                    }

                } else {
                    callback("Data err.")
                }
            } else {
                callback("Err: ${response.message}")
            }
        }
    }

    private fun fetchRelatedAnimeData(animeName: String, selectedTypes: List<String>): String {
        val query = """
        {
          animes(search: "$animeName", limit: 1, kind: "") {
            kind
            episodes
            russian
            status
          }
        }
    """.trimIndent()

        val requestBody = RequestBody.create(mediaType, JSONObject().put("query", query).toString())
        val request = Request.Builder()
            .url(apiUrl)
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response -> //
            if (response.isSuccessful) {
                val responseData = response.body?.string()
                if (responseData != null) {
                    return try {
                        val jsonObject = JSONObject(responseData)
                        val animeData = jsonObject.getJSONObject("data")
                            .getJSONArray("animes")
                            .getJSONObject(0)

                        val episodes = animeData.optString("episodes", "N/A")
                        val russian = animeData.optString("russian", "N/A")
                        val status = animeData.optString("status", "N/A")
                        val kind = animeData.optString("kind", "N/A")

                        if (kind in selectedTypes) {
                            return "$russian: $status: $episodes"
                        } else {
                            return ""
                        }
                    } catch (e: JSONException) {
                        "Parsing error: ${e.message}"
                    }
                } else {
                    return "Anime gathering error."
                }
            } else {
                return "Error: ${response.message}"
            }
        }
    }
}
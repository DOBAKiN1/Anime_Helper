package com.example.animehelper

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.MotionEvent
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)


        val searchButton: Button = findViewById(R.id.searchButton)
        val filtersButton: Button = findViewById(R.id.filtersButton)
        val resultText: TextView = findViewById(R.id.resultText)
        val clearIcon = ContextCompat.getDrawable(this, R.drawable.ic_clear_white)
        val searchField: AutoCompleteTextView  = findViewById(R.id.searchField)
        searchField.threshold = 1

        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, mutableListOf<String>())
        searchField.setAdapter(adapter)


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

        searchField.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                searchField.setCompoundDrawablesWithIntrinsicBounds(
                    null, null, if (s.isNullOrEmpty()) null else clearIcon, null
                )
                suggestNewAnime(s)
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        searchField.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val drawableEnd = searchField.compoundDrawables[2]
                if (drawableEnd != null && event.rawX >= (searchField.right - searchField.compoundPaddingEnd)) {
                    searchField.text.clear()
                    searchField.performClick()
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }

        selectedFilters.add("movie")
        selectedFilters.add("tv")
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

    private fun suggestNewAnime(value: Editable?) {
        val searchField: AutoCompleteTextView = findViewById(R.id.searchField)

        value?.let {
            val query = """
        {
            animes(search: "$value", limit: 5) {
                russian
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
                    if (responseData != null) {
                        try {
                            val jsonObject = JSONObject(responseData)
                            val animeData = jsonObject.getJSONObject("data").getJSONArray("animes")

                            val suggestions = mutableListOf<String>()
                            for (i in 0 until animeData.length()) {
                                val anime = animeData.getJSONObject(i)
                                val russianTitle = anime.optString("russian", "N/A")

                                    suggestions.add(russianTitle)
                                Log.d("Anime", suggestions.toString())

                            }

                            withContext(Dispatchers.Main) {
                                val adapter = searchField.adapter as ArrayAdapter<String>
                                adapter.clear()
                                adapter.addAll(suggestions)
                                adapter.notifyDataSetChanged()

                                searchField.showDropDown()
                            }
                        } catch (e: JSONException) {
                            Log.d("Error", "Error parsing response: $e")
                        }
                    }
                }
            }
        }
    }


    private fun fetchAnimeData(
        searchQuery: String,
        selectedTypes: List<String>,
        callback: (String) -> Unit
    ) {
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
                if (responseData != null) {
                    try {
                        val jsonObject = JSONObject(responseData)
                        val animeData = jsonObject.getJSONObject("data")
                            .getJSONArray("animes")
                            .getJSONObject(0)
                        val name = animeData.optString("name", "N/A")
                        val russian = animeData.optString("russian", "N/A")
                        val english = animeData.optString("english", "N/A")
                        var synonyms = animeData.optJSONArray("synonyms")?.join(", ") ?: "N/A"
                        val episodes = animeData.optInt("episodes", 0)
                        val duration = animeData.optInt("duration", 0)
                        val status = animeData.optString("status", "N/A")
                        val fandubbers = animeData.optJSONArray("fandubbers")?.let { jsonArray ->
                            List(jsonArray.length()) { index -> jsonArray.optString(index) }
                        } ?: emptyList<String>()

                        val relatedAnimes = animeData.optJSONArray("related")?.let { jsonArray ->
                            mutableListOf<String>().apply {

                                for (i in 0 until jsonArray.length()) {
                                    val relatedAnime =
                                        jsonArray.getJSONObject(i).optJSONObject("anime")
                                    if (relatedAnime != null) {
                                        val animeName = relatedAnime.optString("name", "Без имени")
                                        val relatedInfo = async {
                                            fetchRelatedAnimeData(animeName, selectedTypes)
                                        }
                                        if (relatedInfo.await() != "") {
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

                        if (synonyms.isEmpty()) {
                            synonyms = "N/A"
                        }

                        val result = buildString {
                            append(
                                """
                            JP: $name
                            RU: $russian
                            EN: $english
                            Synonyms: $synonyms

                            Ep_amount: $episodes
                            Duration: $duration minutes
                            Approx: $formattedViewingTime hours ~~
                            Status: $status

                            $fandubberSupport

                            Related:
                        """.trimIndent()
                            )

                            if (relatedAnimes.isEmpty()) {
                                append("\nN/A")
                            }
                            else {
                                relatedAnimes.forEach {
                                    append("\n$it")
                                }
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
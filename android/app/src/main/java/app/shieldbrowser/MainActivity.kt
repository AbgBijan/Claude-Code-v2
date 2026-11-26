package app.shieldbrowser

import android.os.Bundle

class MainActivity : BrowserActivity() {
    override val incognito = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // If an incognito session was swiped away (process killed), clean up after it.
        if (!App.incognitoRunning(this)) App.wipeIncognitoData(this)
    }
}

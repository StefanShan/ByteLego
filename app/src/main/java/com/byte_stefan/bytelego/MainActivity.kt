package com.byte_stefan.bytelego

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.TextView


class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.tv_btn).setOnClickListener {
            Log.e("chenshan","点击啦~~")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
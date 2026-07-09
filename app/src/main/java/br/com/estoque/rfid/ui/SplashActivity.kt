package br.com.estoque.rfid.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import br.com.estoque.rfid.databinding.ActivitySplashBinding
import br.com.estoque.rfid.R
import br.com.estoque.rfid.rfid.RfidService

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btRetry.setOnClickListener { connect() }
        connect()
    }

    private fun connect() {
        binding.progressSpinner.visibility = View.VISIBLE
        binding.btRetry.visibility = View.GONE
        binding.tvStatus.text = getString(R.string.splash_connecting)

        RfidService.init(this) { ok ->
            if (isFinishing || isDestroyed) return@init
            if (ok) {
                startActivity(Intent(this, HomeActivity::class.java))
                finish()
            } else {
                binding.progressSpinner.visibility = View.GONE
                binding.tvStatus.text = getString(R.string.splash_failed)
                binding.btRetry.visibility = View.VISIBLE
            }
        }
    }
}

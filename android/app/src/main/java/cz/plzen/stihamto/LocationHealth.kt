package cz.plzen.stihamto

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/**
 * Jednotná kontrola, zda aplikace vůbec může zjistit polohu uživatele.
 * Bez ní by se odjezdy počítaly z výchozího bodu (centrum Plzně / Sady Pětatřicátníků)
 * a uživatel by nechápal, proč vidí zastávku, u které vůbec není.
 */
object LocationHealth {

    enum class Problem {
        NONE,
        NO_PERMISSION,   // Uživatel nepovolil aplikaci přístup k poloze
        SERVICES_OFF     // Poloha je vypnutá v systému (rychlé nastavení)
    }

    fun check(context: Context): Problem {
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return Problem.NO_PERMISSION

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return Problem.NONE
        return if (LocationManagerCompat.isLocationEnabled(lm)) Problem.NONE else Problem.SERVICES_OFF
    }

    fun isLocationServicesEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    fun titleFor(problem: Problem): String = when (problem) {
        Problem.SERVICES_OFF -> "Poloha je vypnutá"
        Problem.NO_PERMISSION -> "Aplikace nemá přístup k poloze"
        Problem.NONE -> ""
    }

    fun descriptionFor(problem: Problem): String = when (problem) {
        Problem.SERVICES_OFF ->
            "Bez polohy nevím, kde jsi, a nemůžu ukázat nejbližší zastávky. Zapni polohu, nebo si zastávku vyhledej ručně."
        Problem.NO_PERMISSION ->
            "Povol aplikaci přístup k poloze, jinak nemůžu ukázat nejbližší zastávky. Zastávku si zatím můžeš vyhledat ručně."
        Problem.NONE -> ""
    }
}

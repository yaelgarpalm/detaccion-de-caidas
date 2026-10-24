package com.example.guardian_iot

import android.content.ContentResolver
import android.provider.ContactsContract.CommonDataKinds.Phone

object ContactsRepository {
    fun read(resolver: ContentResolver, excluded: Set<String> = emptySet()): List<TrustedContact> {
        val seen = excluded.toMutableSet()
        val rows = mutableListOf<TrustedContact>()
        resolver.query(Phone.CONTENT_URI, arrayOf(Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER),
            null, null, "${Phone.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC")?.use { cursor ->
            while (cursor.moveToNext() && !Thread.currentThread().isInterrupted) {
                val phone = PhoneNumbers.normalize(cursor.getString(1).orEmpty()) ?: continue
                if (seen.add(PhoneNumbers.key(phone))) rows.add(TrustedContact(cursor.getString(0).orEmpty(), phone))
            }
        }
        return rows
    }
}

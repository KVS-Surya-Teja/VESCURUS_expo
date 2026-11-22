package com.example.vescurus

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.vescurus.data.local.*
import com.example.vescurus.session.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class) class PhoneStorageTest {
    @Test fun realAndroidFilesRetainCorrectionAndRecoverCorruption() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(context.cacheDir,"storage-test-${System.currentTimeMillis()}")
        val original=CookingSession()
        val entry=IngredientEntry(sessionId=original.id,identities=listOf("Tomato","Onion"),identitySource=EvidenceSource.MIXED,
            quantity=Quantity(250.0,QuantityUnit.GRAMS),quantitySource=EvidenceSource.MANUAL)
        val added=SessionReducer.add(original,entry,1)
        val corrected=SessionReducer.edit(added,entry.id,listOf("Tomato","Onion"),Quantity(240.0,QuantityUnit.GRAMS),EvidenceSource.MANUAL,2)
        val store=SessionStore(dir);store.save(LocalState(active=added));store.save(LocalState(active=corrected))
        assertEquals(corrected,SessionStore(dir).load().active)
        File(dir,"cooking.json").writeText("corrupted test file")
        assertEquals(added,SessionStore(dir).load().active)
    }
}

package com.example.vescurus

import com.example.vescurus.session.*
import com.example.vescurus.data.local.*
import com.example.vescurus.data.network.*
import com.example.vescurus.model.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionRegressionTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun entry(s: CookingSession,id: String="event-1",names: List<String> = listOf("Tomato"),amount: Double=100.0) =
        IngredientEntry(id=id,sessionId=s.id,identities=names,identitySource=EvidenceSource.MANUAL,quantity=Quantity(amount,QuantityUnit.GRAMS),quantitySource=EvidenceSource.MANUAL)
    @Test fun duplicateCommitIsIdempotentButLaterPourIsSeparate() {
        val s=CookingSession();val e=entry(s)
        val first=SessionReducer.add(s,e,1)
        assertEquals(first,SessionReducer.add(first,e,2))
        val second=SessionReducer.add(first,entry(s,"event-2"),3)
        assertEquals(2,second.entries.size)
        assertEquals(200.0,second.entries.sumOf { it.quantity!!.value },0.001)
    }
    @Test fun mixedBatchHasOneCombinedQuantity() {
        val s=CookingSession();val r=SessionReducer.add(s,entry(s,names=listOf("Tomato","Onion"),amount=250.0),1)
        assertEquals(1,r.entries.size);assertEquals(listOf("Tomato","Onion"),r.ingredientNames)
        assertEquals(250.0,r.entries.single().quantity!!.value,.001)
    }
    @Test fun correctionAndUndoKeepAudit() {
        val s=CookingSession();val added=SessionReducer.add(s,entry(s),1)
        val edited=SessionReducer.edit(added,"event-1",listOf("Onion"),Quantity(80.0,QuantityUnit.GRAMS),EvidenceSource.MANUAL,2)
        val undone=SessionReducer.undo(edited,"event-1",3)
        assertTrue(undone.ingredientNames.isEmpty());assertEquals(2,undone.entries.single().audits.size)
        assertEquals("Tomato",undone.entries.single().audits.first().previousLabel)
    }
    @Test fun preparationDoesNotImplyTransfer() {
        val s=CookingSession();val added=SessionReducer.add(s,entry(s),1)
        assertEquals(EntryStatus.PREPARED,added.entries.single().status)
        assertEquals(EntryStatus.TRANSFER_CONFIRMED,SessionReducer.transfer(added,"event-1",2).entries.single().status)
    }
    @Test fun quantitiesKeepUnitsAndRejectInvalidValues() {
        assertNotEquals(Quantity(1.0,QuantityUnit.COUNT),Quantity(1.0,QuantityUnit.GRAMS))
        listOf(0.0,-1.0,Double.NaN,Double.POSITIVE_INFINITY).forEach { v -> assertThrows(IllegalArgumentException::class.java) { Quantity(v,QuantityUnit.GRAMS) } }
    }
    @Test fun secondIngredientCanBeAddedAfterRecipeSelection() {
        val s=CookingSession(recipe=BUILT_IN_RECIPES.first(),state="COOKING")
        val result=SessionReducer.add(s,entry(s,names=listOf("Onion")),1)
        assertEquals(s.recipe,result.recipe);assertEquals("COOKING",result.state);assertEquals(listOf("Onion"),result.ingredientNames)
    }
    @Test fun savedStateSurvivesNewStoreInstanceAndExport() {
        val dir=temporary.newFolder();val s=CookingSession();val state=LocalState(active=SessionReducer.add(s,entry(s),1))
        SessionStore(dir).save(state)
        val reloaded=SessionStore(dir).load();assertEquals(state,reloaded)
        val exported=Json.decodeFromString<LocalState>(SessionStore(dir).export(state));assertEquals(state,exported)
    }
    @Test fun olderSessionsLoadWithoutInventingFinishedPhotoOrElapsedTime() {
        val legacy=Json.decodeFromString<CookingSession>("""{"id":"legacy-finished","completedAt":1234,"state":"DONE"}""")
        assertNull(legacy.historyTitle)
        assertNull(legacy.cookingStartedAt)
        assertEquals(0L,legacy.activeCookingMs)
        assertNull(legacy.finalPhotoPath)
        assertNull(legacy.finalPhotoCapturedAt)
    }
    @Test fun completedDishMetadataAndPreparationSurviveStoreAndExport() {
        val dir=temporary.newFolder()
        val base=CookingSession()
        val cooked=base.copy(entries=listOf(entry(base).copy(ingredientPreparation="diced, raw")),
            state="DONE",completedAt=3000,cookingStartedAt=1000,activeCookingMs=1500,
            historyTitle="My tomato dish",finalPhotoPath=File(dir,"finished.jpg").absolutePath,finalPhotoCapturedAt=3100)
        val state=LocalState(active=CookingSession(),history=listOf(cooked))
        val store=SessionStore(dir)
        store.save(state)
        assertEquals(cooked,SessionStore(dir).load().history.single())
        assertEquals(cooked,Json.decodeFromString<LocalState>(store.export(state)).history.single())
    }
    @Test fun corruptedPrimaryRecoversPreviousStateWithoutClobberingFiles() {
        val dir=temporary.newFolder();val store=SessionStore(dir);val first=LocalState()
        store.save(first);store.save(first.copy(active=first.active.copy(version=1)))
        File(dir,"cooking.json").writeText("{broken")
        assertEquals(first,SessionStore(dir).load())
        store.save(first.copy(active=first.active.copy(version=2)))
        assertTrue(dir.listFiles()!!.any { it.name.contains("corrupt") })
        assertEquals(2L,SessionStore(dir).load().active.version)
    }
    @Test fun demonstrationCannotOverwriteLiveSession() {
        val dir=temporary.newFolder();val live=LocalState();val demo=LocalState(active=CookingSession(demonstration=true))
        val store=SessionStore(dir);store.save(live);store.save(demo,true)
        assertEquals(live,store.load());assertEquals(demo,store.load(true))
    }
    @Test fun realSpoonacularPayloadPreservesSourceAndNutrition() {
        val text=javaClass.getResourceAsStream("/recipes/actual-spoonacular-search.json")!!.bufferedReader().use { it.readText() }
        val response=Json { ignoreUnknownKeys=true }.decodeFromString<SpoonacularSearch>(text)
        val mapped=RecipeMapper.map(response.results.first(),1)!!
        assertEquals("spoon-${response.results.first().id}",mapped.id);assertEquals("Spoonacular",mapped.source)
        assertTrue(mapped.steps.isNotEmpty());assertNotNull(mapped.calories);assertTrue(mapped.targets.isNotEmpty())
    }
    @Test fun missingNutritionAndInstructionsAreNotFabricated() {
        val d=SpoonacularRecipeDetail(1,"Example",analyzedInstructions=listOf(SpoonacularInstruction(listOf(SpoonacularStep(1,"Saute onions in a pan.")))))
        val r=RecipeMapper.map(d,1)!!;assertNull(r.calories);assertNull(r.proteinG)
        assertNull(RecipeMapper.map(d.copy(analyzedInstructions=emptyList()),1))
    }
    @Test fun panCompatibilityDoesNotMatchSubstringsInsideOtherWords() {
        val r=BUILT_IN_RECIPES.first()
        assertTrue(RecipeMapper.panCompatible(r))
        assertFalse(RecipeMapper.panCompatible(r.copy(steps=listOf(RecipeStep(0,1,"Transfer to an oven and bake.")))))
    }
    @Test fun observedWaterCookingRecipeIsRejectedForTheTawa() {
        val r=BUILT_IN_RECIPES.first().copy(steps=listOf(RecipeStep(0,1,"Peel and quarter the potatoes, and cook in lightly salted water until tender. Fry the pepper in olive oil.")))
        assertFalse(RecipeMapper.panCompatible(r))
    }
    @Test fun mistypedIpv4AddressIsRejectedBeforeConnecting() {
        assertTrue(com.example.vescurus.network.isValidPeerHost("192.168.0.101"))
        assertTrue(com.example.vescurus.network.isValidPeerHost("guide.local"))
        assertFalse(com.example.vescurus.network.isValidPeerHost("7192.168.0.101"))
        assertFalse(com.example.vescurus.network.isValidPeerHost("192.168.0.999"))
        assertFalse(com.example.vescurus.network.isValidPeerHost("http://192.168.0.101"))
    }
    @org.junit.Test fun preparationOnlyCorrectionAdvancesRevisionAndRetainsAudit() {
        val s=com.example.vescurus.session.CookingSession()
        val e=com.example.vescurus.session.IngredientEntry(sessionId=s.id,identities=listOf("Carrot"),
            identitySource=com.example.vescurus.session.EvidenceSource.MANUAL,ingredientPreparation="raw")
        val added=com.example.vescurus.session.SessionReducer.add(s,e,1)
        val edited=com.example.vescurus.session.SessionReducer.edit(added,e.id,e.identities,null,null,2,"diced")
        org.junit.Assert.assertEquals(added.version+1,edited.version)
        org.junit.Assert.assertEquals("diced",edited.entries.single().ingredientPreparation)
        org.junit.Assert.assertEquals("raw",edited.entries.single().audits.single().previousPreparation)
    }
    @org.junit.Test fun savedPlanningNotesSurviveLocalStorageReload() {
        val dir=temporary.newFolder()
        val state=LocalState(active=CookingSession(planningNotes=listOf("Vegetarian", "Ready in 15 minutes")))
        SessionStore(dir).save(state,false)
        org.junit.Assert.assertEquals(state.active.planningNotes,SessionStore(dir).load(false).active.planningNotes)
    }
}

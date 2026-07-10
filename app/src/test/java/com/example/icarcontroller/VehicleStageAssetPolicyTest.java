package com.example.icarcontroller;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class VehicleStageAssetPolicyTest {
    @Test
    public void mapsOnlyThePackagedVehicleStageOrigin() {
        assertEquals(
                "vehicle_stage/index.html",
                VehicleStageAssetPolicy.assetPathFor("https://icar.local/vehicle_stage/index.html")
        );
        assertEquals(
                "vehicle_stage/vendor/three.min.js",
                VehicleStageAssetPolicy.assetPathFor("https://icar.local/vehicle_stage/vendor/three.min.js")
        );
        assertNull(VehicleStageAssetPolicy.assetPathFor("https://example.com/vehicle_stage/index.html"));
        assertNull(VehicleStageAssetPolicy.assetPathFor("http://icar.local/vehicle_stage/index.html"));
    }

    @Test
    public void rejectsTraversalAndUnknownPaths() {
        assertNull(VehicleStageAssetPolicy.assetPathFor("https://icar.local/vehicle_stage/../secret"));
        assertNull(VehicleStageAssetPolicy.assetPathFor("https://icar.local/other/index.html"));
        assertNull(VehicleStageAssetPolicy.assetPathFor("not a url"));
    }

    @Test
    public void returnsStableMimeTypesForAllStageAssets() {
        assertEquals("text/html", VehicleStageAssetPolicy.mimeType("vehicle_stage/index.html"));
        assertEquals("text/css", VehicleStageAssetPolicy.mimeType("vehicle_stage/stage.css"));
        assertEquals("application/javascript", VehicleStageAssetPolicy.mimeType("vehicle_stage/stage.js"));
        assertEquals("model/gltf-binary", VehicleStageAssetPolicy.mimeType("vehicle_stage/x3.glb"));
    }
}

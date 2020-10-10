package com.example.isight;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.here.sdk.core.GeoCircle;
import com.here.sdk.core.GeoCoordinates;
import com.here.sdk.core.LanguageCode;
import com.here.sdk.core.errors.InstantiationErrorException;
import com.here.sdk.routing.Maneuver;
import com.here.sdk.routing.PedestrianOptions;
import com.here.sdk.routing.Route;
import com.here.sdk.routing.RoutingEngine;
import com.here.sdk.routing.Section;
import com.here.sdk.routing.Waypoint;
import com.here.sdk.search.SearchEngine;
import com.here.sdk.search.SearchOptions;
import com.here.sdk.search.TextQuery;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RouteHandler {

    private static final int LOCATION_PERM_CODE = 656;
    private Context context;
    private Activity activity;
    private boolean isPermGranted = false;

    private RoutingEngine routingEngine;
    private SearchEngine searchEngine;
    private FusedLocationProviderClient fusedLocationClient;
    private VoiceHandler voiceHandler;
    public Thread routeThread;


    RouteHandler(Context context, Activity activity, VoiceHandler voiceHandler) {
        this.context = context;
        this.activity = activity;
        this.voiceHandler = voiceHandler;
    }

    void initRouteEngine() {

        try {
            checkPermissions();
            routingEngine = new RoutingEngine();
            searchEngine = new SearchEngine();
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(context);

        } catch (InstantiationErrorException e) {
            throw new RuntimeException("Initialization of RoutingEngine failed: " + e.error.name());
        }
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            isPermGranted = true;
        }
        else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION},LOCATION_PERM_CODE);
            }
        }

    }

     void initiateRouting(final String destinationQuery) {
        if (isPermGranted) {
            routeThread = new Thread(() -> {
                while (true) {
                    fusedLocationClient.getLastLocation()
                            .addOnSuccessListener(activity, location -> {
                                if (location != null)
                                    getDestination(location, destinationQuery);
                                else
                                    voiceHandler.forceText("Please turn on location");
                            });
                    try {
                        Thread.sleep(15000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            });

            routeThread.start();
        }
        else {
            checkPermissions();
        }
    }

    public void stopRouting() {
        routeThread.interrupt();
    }

    private void getDestination(Location location, String destinationQuery) {
        GeoCoordinates selfCoords = new GeoCoordinates(location.getLatitude(), location.getLongitude());
        GeoCircle selfArea = new GeoCircle(selfCoords, 100000);
        SearchOptions searchOptions = new SearchOptions(LanguageCode.EN_US, 1);
        TextQuery routeQuery = new TextQuery(destinationQuery, selfArea);
        searchEngine.search(routeQuery, searchOptions, (searchError, places) -> {
            if (searchError != null) {
                Log.d("SEARCH ERROR", searchError.toString());
                voiceHandler.forceText("Could not find your location.");
                return;
            }

            Waypoint startPoint = new Waypoint(selfCoords);
            Waypoint destinationPoint = new Waypoint(places.get(0).getGeoCoordinates());

            List<Waypoint> waypoints = new ArrayList<>(Arrays.asList(startPoint, destinationPoint));

            routingEngine.calculateRoute(
                    waypoints,
                    new PedestrianOptions(),
                    (routingError, list) -> {
                        if (routingError != null) {
                            Log.d("ROUTING ERROR", routingError.toString());
                            voiceHandler.forceText("Path could not be set");
                            return;
                        }

                        Route route = list.get(0);
                        List<Section> sections = route.getSections();
                        for (Section section : sections) {
                            List<Maneuver> maneuvers = section.getManeuvers();
                            for (int x = 0; x < maneuvers.size(); x++) {
                                Log.d("MANEUVERS:", maneuvers.get(x).getText() +  "@" + maneuvers.get(x).getAction().name());
                                if (x == 2)
                                    break;

                                String directions = maneuvers.get(x).getText();
                                directions = directions.replace("m", "steps");
                                directions = directions.replace("km", "kilometres");
                                voiceHandler.queueText(directions);
                            }
                        }
                    }
            );
        });
    }
}

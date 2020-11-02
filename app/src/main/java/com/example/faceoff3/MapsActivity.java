package com.example.faceoff3;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.AsyncTask;
import android.os.Bundle;

import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.common.api.Status;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.libraries.places.api.Places;
import com.google.android.libraries.places.api.model.Place;
import com.google.android.libraries.places.api.model.RectangularBounds;
import com.google.android.libraries.places.api.net.PlacesClient;
import com.google.android.libraries.places.widget.AutocompleteSupportFragment;
import com.google.android.libraries.places.widget.listener.PlaceSelectionListener;
import com.google.maps.android.data.Feature;
import com.google.maps.android.data.geojson.GeoJsonFeature;
import com.google.maps.android.data.geojson.GeoJsonLayer;
import com.google.maps.android.data.geojson.GeoJsonPointStyle;

import org.jetbrains.annotations.NotNull;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Objects;

import androidx.core.app.ActivityCompat;
import androidx.fragment.app.FragmentActivity;


import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.MarkerOptions;


public class MapsActivity extends FragmentActivity {
    private static final String TAG = "MapsActivity";
    private GoogleMap mMap;
    private final static String mLogTag = "GeoJsonLayers";
    public String countyCode = "";
    public GeoJsonLayer layer2;
    public HashMap<String, String> countyCodeMap = new HashMap<>();
    private LatLngBounds mMapBoundary;
    private Location mUserPosition;


    /* this function pulls the county polygons from the 2010 Census GeoJSON on-device resource */
    private void retrieveFileFromResource() {
        try {
            layer2 = new GeoJsonLayer(mMap, R.raw.census2010counties20m, Objects.requireNonNull(this));

        } catch (IOException e) {
            Log.e(mLogTag, "GeoJSON file could not be read");
        } catch (JSONException e) {
            Log.e(mLogTag, "GeoJSON file could not be converted to a JSONObject");
        }
    }


    /* these next two functions pull live COVID-19 data from URL stored in /res/values/strings.xml */
    private void retrieveCovidFileFromUrl() {
        new DownloadCovidGeoJsonFile().execute(getString(R.string.covid_geojson_url));
    }

    private void retrieveCountyFileFromUrl() {
        new DownloadCountyGeoJsonFile().execute(getString(R.string.county_geojson_url));
    }

    private class DownloadCovidGeoJsonFile extends AsyncTask<String, Void, GeoJsonLayer> {


        @Override
        protected GeoJsonLayer doInBackground(String... params) {
            try {

                // Open a stream from the URL
                InputStream stream = new URL(params[0]).openStream();

                String line;
                StringBuilder result = new StringBuilder();
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream));

                while ((line = reader.readLine()) != null) {
                    // Read and save each line of the stream
                    result.append(line);
                }

                // Close the stream
                reader.close();
                stream.close();

                return new GeoJsonLayer(mMap, new JSONObject(result.toString()));
            } catch (IOException e) {
                Log.e(mLogTag, "GeoJSON file could not be read");
            } catch (JSONException e) {
                Log.e(mLogTag, "GeoJSON file could not be converted to a JSONObject");
            }
            return null;
        }

        @Override
        protected void onPostExecute(GeoJsonLayer layer) {
            if (layer != null) {
                addGeoJsonLayerToMap(layer);
            }
        }
    }

    private class DownloadCountyGeoJsonFile extends AsyncTask<String, Void, GeoJsonLayer> {


        @Override
        protected GeoJsonLayer doInBackground(String... params) {

            try {

                // Open a stream from the URL
                InputStream stream = new URL(params[0]).openStream();

                String line;
                String keyString;

                // StringBuilder result = new StringBuilder();
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream));

                while ((line = reader.readLine()) != null) {
                    // Read and save each line of the stream
                    if (line.contains("0500000US")) { // find the county code in the county geojson
                        keyString = line.substring((line.indexOf("0500000US") + 9), (line.indexOf("0500000US") + 14));  // this should grab the 5 numbers right after the indexed "0500000US" portion of the string
                        countyCodeMap.put(keyString, line.substring(0, line.length() - 1)); // put this key value pair in the hashMap countyCodeMap, chopping the last comma off each line beforehand
                    }
                }
                // line = line.substring(0, line.length() - 1);  // chop off the last character (a comma)

                line = countyCodeMap.get(countyCode);
                line = "{\n" +
                        "\"type\": \"FeatureCollection\",\n" +
                        "\"features\": [\n" + line + "]\n" +
                        "}\n";                                // make the single line match a valid GeoJSON file

                // Close the stream
                reader.close();
                stream.close();

                return new GeoJsonLayer(mMap, new JSONObject(line));
            } catch (IOException e) {
                Log.e(mLogTag, "GeoJSON file could not be read");
            } catch (JSONException e) {
                Log.e(mLogTag, "GeoJSON file could not be converted to a JSONObject");
            }
            return null;
        }

        @Override
        protected void onPostExecute(GeoJsonLayer layer) {
            if (layer != null) {
                layer.addLayerToMap();
            }
        }
    }

    private void addGeoJsonLayerToMap(GeoJsonLayer layer) {

        addColorsToMarkers(layer);
        layer.addLayerToMap();

        // receiving features via GeoJsonLayer clicks.
        layer.setOnFeatureClickListener(new GeoJsonLayer.GeoJsonOnFeatureClickListener() {
            @Override
            public void onFeatureClick(Feature feature) {   // TODO: this is apparently where a problem is that keeps the map from registering more than 1 county-boundary drawing click. What should be expected from this function onFeatureClick?
                countyCode = feature.getProperty("FIPS");

                String recommendation = "";
                /* right now this is the same calculation as below in the magnitudeToColor function,
                 * but in the future we can change it to incorporate the user's current face-touching
                 * to hand-washing ratio, to give a more tailored recommendation to the user */
                if (Double.parseDouble(feature.getProperty("Incident_Rate")) < 2000) {
                    recommendation = "exercise caution in this area";
                } else if (Double.parseDouble(feature.getProperty("Incident_Rate")) < 4000) {
                    recommendation = "exercise exaggerated caution in this area";
                } else if (Double.parseDouble(feature.getProperty("Incident_Rate")) < 6000) {
                    recommendation = "exercise extreme caution in this area";
                } else {
                    recommendation = "avoid unnecessary travel to or within this area";
                }

                retrieveCountyFileFromUrl();

                Toast.makeText(getApplicationContext(),
                        "Based on your habits, FaceOff recommends that you " + recommendation,
                        Toast.LENGTH_LONG).show();// TODO: seems like this only gets called once and done

                /* this is where we'll add and remove each county's GeoJSON layer */
             /*   if ((feature.getProperty("STATE") + feature.getProperty("COUNTY")).equals(feature.getProperty("FIPS"))) {

                }*/
            }

        });
    }

    /**
     * Adds a point style to all features to change the color of the marker based on its magnitude
     * property
     */
    private void addColorsToMarkers(GeoJsonLayer layer) {
        // Iterate over all the features stored in the layer
        for (GeoJsonFeature feature : layer.getFeatures()) {
            // Check if the magnitude property exists
            if (feature.getProperty("Incident_Rate") != null && feature.hasProperty("Admin2")) {
                double magnitude = Double.parseDouble(feature.getProperty("Incident_Rate"));

                // Get the icon for the feature
                BitmapDescriptor pointIcon = BitmapDescriptorFactory
                        .defaultMarker(magnitudeToColor(magnitude));

                // Create a new point style
                GeoJsonPointStyle pointStyle = new GeoJsonPointStyle();

                // Set options for the point style
                pointStyle.setIcon(pointIcon);
                pointStyle.setTitle("1 in " + Math.round(100000 / magnitude) + " people");
                pointStyle.setSnippet("COVID-19 incidence rate in " + feature.getProperty("Admin2") + " County, " + feature.getProperty("Province_State"));
                pointStyle.setAlpha((float) 0.6);

                // Assign the point style to the feature
                feature.setPointStyle(pointStyle);
            }
        }
    }

    /**
     * Assigns a color based on the given magnitude
     */
    private static float magnitudeToColor(double magnitude) {
        if (magnitude < 2000) {
            return BitmapDescriptorFactory.HUE_YELLOW;
        } else if (magnitude < 4000) {
            return BitmapDescriptorFactory.HUE_ORANGE;
        } else if (magnitude < 6000) {
            return BitmapDescriptorFactory.HUE_RED;
        } else {
            return BitmapDescriptorFactory.HUE_VIOLET;
        }
    }


    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_maps);
        // Obtain the supportMapFragment and get notified when the map is ready to be used

        /* Initialize map fragment */
        SupportMapFragment mapFragment = (SupportMapFragment)
                getSupportFragmentManager().findFragmentById(R.id.google_map);
        mapFragment.getMapAsync(new OnMapReadyCallback() {
            @Override
            public void onMapReady(GoogleMap googleMap) {
                /* when map is loaded */
                mMap = googleMap;

                /* get latest COVID-19 data */
                retrieveCovidFileFromUrl();

                /* get the county polygons from the 2010 Census GeoJSON */
                //     retrieveFileFromResource();

                if (ActivityCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
                mMap.setMyLocationEnabled(true);
                //setCameraView();
//                mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(myLocation ));


                LatLngBounds coloradoBounds = new LatLngBounds(
                        new LatLng(37.30, -109.00), // SW bounds
                        new LatLng(41.83, -100.99)  // NE bounds
                );
                mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(coloradoBounds.getCenter(), 8));



/*                try {
                    GeoJsonLayer layer = new GeoJsonLayer(mMap, R.raw.covid19cases, getContext());
                    GeoJsonPolygonStyle polyStyle = layer.getDefaultPolygonStyle();

                    GeoJsonPointStyle pointStyle = layer.getDefaultPointStyle();
                    pointStyle.setAlpha((float)0.2);
                    // String geoJsonFeature = GeoJsonFeature.;

                    polyStyle.setStrokeColor(Color.CYAN);
                    polyStyle.setStrokeWidth(2);
                    layer.addLayerToMap();
                } catch (IOException e) {
                } catch (JSONException e) {
                }*/
                mMap.setOnMapClickListener(new GoogleMap.OnMapClickListener() {
                    @Override
                    public void onMapClick(LatLng latLng) {
                        /* when clicked on map */
                        /* initialize marker options */
                        MarkerOptions markerOptions = new MarkerOptions();
                        /* set position of marker */
                        markerOptions.position(latLng);
                        /* set title of marker */
                        markerOptions.title(latLng.latitude + " : " + latLng.longitude);

                        /* remove all markers */
                        // googleMap.clear();

                        /* animating to zoom the marker */
                        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                                latLng, 10

                        ));
                        /* add marker on map */
                        mMap.addMarker(markerOptions);

                    }
                });
            }
        });

            String apiKey = getString(R.string.api_key);

            if(!Places.isInitialized()){
                Places.initialize(getApplicationContext(), apiKey);
            }

            PlacesClient placesClient = Places.createClient(getApplicationContext());

            // Initialize the AutocompleteSupportFragment.
            AutocompleteSupportFragment autocompleteFragment = (AutocompleteSupportFragment)
                    getSupportFragmentManager().findFragmentById(R.id.autocomplete_fragment);


            autocompleteFragment.setLocationBias(RectangularBounds.newInstance(
                    new LatLng(37.20, -109.00), // SW bounds
                    new LatLng(41.73, -100.99)  // NE bounds
            ));

            autocompleteFragment.setCountries("US");

            // Specify the types of place data to return.
    autocompleteFragment.setPlaceFields(Arrays.asList(Place.Field.ID, Place.Field.NAME));

            // Set up a PlaceSelectionListener to handle the response.
    autocompleteFragment.setOnPlaceSelectedListener(new PlaceSelectionListener() {
                @Override
                public void onPlaceSelected(@NotNull Place place) {
                    // TODO: Get info about the selected place.
                    mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(place.getLatLng(), 10));    // TODO: why doesn't this work? also, the new autocomplete search bar is hiding the myLocation button
                    Log.i(TAG, "Place: " + place.getName() + ", " + place.getId());
                }


                @Override
                public void onError(@NotNull Status status) {
                    // TODO: Handle the error.
                    Log.i(TAG, "An error occurred: " + status);
                }
            });





    }
}



/*    private void setCameraView(){
        double bottomBoundary = mUserPosition.getLatitude() - .1;
        double leftBoundary = mUserPosition.getLongitude() - .1;
        double topBoundary = mUserPosition.getLatitude() + .1;
        double rightBoundary = mUserPosition.getLongitude() + .1;

        mMapBoundary = new LatLngBounds(
                new LatLng(bottomBoundary, leftBoundary),
                new LatLng(topBoundary, rightBoundary)
        );


        mMap.moveCamera(CameraUpdateFactory.newLatLngBounds(mMapBoundary, 10));
    }*/

    //protected GoogleMap getMap() {
      //  return mMap;
    //}

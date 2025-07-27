// --- DataFragment.java ---
package com.example.myapplication.fragments.subfragments;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.myapplication.R; // Make sure this path is correct for your project

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

public class DataFragment extends Fragment {

    private static final String TAG = "DataFragment";
    private List<Object> data; // Made non-final to allow updates
    private final List<CardView> cardViews = new ArrayList<>(); // List to hold card views;
    private LinearLayout containerLayout; // Added to easily access and clear/add views

    // Constructor to pass the list of data
    public DataFragment(List<Object> data) {
        this.data = data;
    }

    // Factory method to create new instances
    public static DataFragment newInstance(ArrayList<Object> data) { // Use ArrayList for Parcelable
        DataFragment fragment = new DataFragment(data);
        Bundle args = new Bundle();
        // IMPORTANT: Directly passing complex objects like Vector3f or Quaternionf
        // via Bundle requires them to be Parcelable or Serializable.
        // For simple demonstration, let's assume they are or you convert them to arrays/lists of floats.
        // For production, you'd serialize them or pass simple types.
        // Example for Parcelable/Serializable:
        // args.putSerializable("data_list", (Serializable) data); // If objects are Serializable
        // Or if you convert them to arrays of floats, e.g., for Vector3f:
        ArrayList<float[]> vectorData = new ArrayList<>();
        if (data != null) {
            for (Object item : data) {
                if (item instanceof Vector3f) {
                    vectorData.add(new float[]{((Vector3f) item).x, ((Vector3f) item).y, ((Vector3f) item).z});
                } else if (item instanceof Quaternionf) {
                    vectorData.add(new float[]{((Quaternionf) item).x, ((Quaternionf) item).y, ((Quaternionf) item).z, ((Quaternionf) item).w});
                }
                // ... handle other types for serialization ...
            }
        }
        // A more robust approach might involve JSON serialization if data is complex
        // For this example, let's just pass simple types or assume complex types are serializable if possible.
        // A common way for custom objects is to implement Parcelable.
        // Since JOML types are not Parcelable by default, you'd need a wrapper or convert to float arrays.

        // For demonstration, let's simplify and just pass primitive types or Strings directly if possible
        // For complex custom types, consider making them Parcelable or serializing them to JSON/byte arrays.
        // For now, let's assume you'd handle the serialization of Vector3f/Quaternionf outside if needed.
        // A simple example for a list of strings:
        if (data != null && !data.isEmpty()) {
            ArrayList<String> stringRepresentations = new ArrayList<>();
            for (Object item : data) {
                stringRepresentations.add(item.toString()); // Simple string representation
            }
            args.putStringArrayList("data_list", stringRepresentations);
        }

        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_data, container, false);

        // Get a reference to the LinearLayout where CardViews will be added
        containerLayout = view.findViewById(R.id.data_card_container_layout); // Initialize containerLayout

        // Initial display of data
        displayData();

        return view;
    }

    /**
     * Updates the data displayed in the DataFragment.
     * This method should be called on the main UI thread.
     *
     * @param newData The new list of objects to display.
     */
    public void updateData(List<Object> newData) {
        this.data = newData;
        if (containerLayout != null) {
            containerLayout.removeAllViews(); // Clear existing views
            cardViews.clear(); // Clear existing card views list
            displayData(); // Redraw with new data
        } else {
            Log.w(TAG, "containerLayout is null, cannot update data. Fragment view might not be created yet.");
        }
    }

    /**
     * Helper method to display the current data in the UI.
     */
    private void displayData() {
        if (data != null && containerLayout != null) {
            for (Object o : data) {
                CardView card = createCardFromObject(o, 0); // Pass initial depth 0
                if (card != null) {
                    containerLayout.addView(card);
                    cardViews.add(card); // Keep track of created card views
                }
            }
            if (data.isEmpty()) {
                addNoDataMessage(containerLayout);
            }
        } else if (containerLayout != null) {
            Log.w(TAG, "Data list is null or empty, no cards to display.");
            addNoDataMessage(containerLayout);
        }
    }

    /**
     * Adds a "No data provided" message to the layout.
     * @param parentLayout The LinearLayout to add the message to.
     */
    private void addNoDataMessage(LinearLayout parentLayout) {
        TextView noDataText = new TextView(requireContext());
        noDataText.setText("No data provided to display.");
        noDataText.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16));
        parentLayout.addView(noDataText);
    }

    /**
     * Dynamically creates a CardView based on the type of the given object.
     * Includes a collapsible header and content area.
     *
     * @param o The object to display in the card.
     * @param depth Current nesting depth, used for card titles and left margin (indentation).
     * @return A configured CardView, or null if the object type is not supported.
     */
    private CardView createCardFromObject(Object o, int depth) {
        CardView cardView = new CardView(requireContext());

        // Define LayoutParams for the CardView
        LinearLayout.LayoutParams cardLayoutParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, // Width
                LinearLayout.LayoutParams.WRAP_CONTENT  // Height
        );
        // Add left margin based on depth for visual nesting
        cardLayoutParams.setMargins(
                dpToPx(16 + depth * 8), // Left margin (indent for nested cards)
                dpToPx(8),  // Top margin
                dpToPx(16), // Right margin
                dpToPx(8)   // Bottom margin
        );
        cardView.setLayoutParams(cardLayoutParams);

        // Set CardView properties
        cardView.setRadius(dpToPx(8));
        cardView.setCardElevation(dpToPx(4));
        try {
            cardView.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.card_background));
        } catch (Exception e) {
            Log.e(TAG, "Error setting card background color, check colors.xml: " + e.getMessage());
            cardView.setCardBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.white)); // Fallback
        }

        // Create a parent LinearLayout inside the CardView to hold header and content
        LinearLayout cardContentWrapper = new LinearLayout(requireContext());
        cardContentWrapper.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        cardContentWrapper.setOrientation(LinearLayout.VERTICAL);

        // Create the Header Layout (title + arrow icon)
        LinearLayout headerLayout = new LinearLayout(requireContext());
        headerLayout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        headerLayout.setOrientation(LinearLayout.HORIZONTAL);
        headerLayout.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16));
        try {
            headerLayout.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.header_background_color));
        } catch (Exception e) {
            Log.e(TAG, "Error setting header background color, check colors.xml: " + e.getMessage());
            headerLayout.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.darker_gray)); // Fallback
        }
        headerLayout.setClickable(true);
        headerLayout.setFocusable(true);

        // Header Title TextView
        TextView headerTitleTextView = new TextView(requireContext());
        LinearLayout.LayoutParams titleLayoutParams = new LinearLayout.LayoutParams(
                0, // width
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.0f // weight
        );
        headerTitleTextView.setLayoutParams(titleLayoutParams);
        headerTitleTextView.setTextSize(18f); // sp
        try {
            headerTitleTextView.setTextColor(ContextCompat.getColor(requireContext(), R.color.header_text_color));
        } catch (Exception e) {
            Log.e(TAG, "Error setting header text color, check colors.xml: " + e.getMessage());
            headerTitleTextView.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white)); // Fallback
        }

        // Dropdown Arrow ImageView
        ImageView arrowIcon = new ImageView(requireContext());
        LinearLayout.LayoutParams arrowLayoutParams = new LinearLayout.LayoutParams(
                dpToPx(24), // width
                dpToPx(24)  // height
        );
        arrowIcon.setLayoutParams(arrowLayoutParams);
        arrowIcon.setImageResource(R.drawable.ic_arrow_down); // Assuming you have drawable for arrow down
        arrowIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.header_text_color), android.graphics.PorterDuff.Mode.SRC_IN);
        try {
            arrowIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.header_text_color), android.graphics.PorterDuff.Mode.SRC_IN);
        } catch (Exception e) {
            Log.e(TAG, "Error setting arrow icon color, check colors.xml: " + e.getMessage());
            arrowIcon.setColorFilter(ContextCompat.getColor(requireContext(), android.R.color.white), android.graphics.PorterDuff.Mode.SRC_IN); // Fallback
        }


        headerLayout.addView(headerTitleTextView);
        headerLayout.addView(arrowIcon);

        // Content Layout (initially hidden)
        LinearLayout contentLayout = new LinearLayout(requireContext());
        contentLayout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(16));
        contentLayout.setVisibility(View.GONE); // Initially hidden

        // Populate header and content based on object type
        if (o instanceof Vector3f) {
            Vector3f vec = (Vector3f) o;
            headerTitleTextView.setText(String.format("Vector3f (Depth: %d)", depth));
            addTextViewToLayout(contentLayout, String.format("X: %.4f", vec.x));
            addTextViewToLayout(contentLayout, String.format("Y: %.4f", vec.y));
            addTextViewToLayout(contentLayout, String.format("Z: %.4f", vec.z));
        } else if (o instanceof Quaternionf) {
            Quaternionf quat = (Quaternionf) o;
            headerTitleTextView.setText(String.format("Quaternionf (Depth: %d)", depth));
            addTextViewToLayout(contentLayout, String.format("X: %.4f", quat.x));
            addTextViewToLayout(contentLayout, String.format("Y: %.4f", quat.y));
            addTextViewToLayout(contentLayout, String.format("Z: %.4f", quat.z));
            addTextViewToLayout(contentLayout, String.format("W: %.4f", quat.w));
        } else if (o instanceof List) {
            // Handle nested lists by creating sub-cards
            headerTitleTextView.setText(String.format("List (Depth: %d, Size: %d)", depth, ((List<?>) o).size()));
            for (Object item : (List<?>) o) {
                CardView nestedCard = createCardFromObject(item, depth + 1); // Increase depth for nesting
                if (nestedCard != null) {
                    contentLayout.addView(nestedCard);
                }
            }
        } else {
            // Default for other object types, just use toString()
            headerTitleTextView.setText(String.format("Object (%s, Depth: %d)", o.getClass().getSimpleName(), depth));
            addTextViewToLayout(contentLayout, o.toString());
        }

        // Set click listener for header to toggle content visibility
        headerLayout.setOnClickListener(v -> {
            if (contentLayout.getVisibility() == View.VISIBLE) {
                contentLayout.setVisibility(View.GONE);
                arrowIcon.animate().rotation(0).setDuration(200).start(); // Rotate down
            } else {
                contentLayout.setVisibility(View.VISIBLE);
                arrowIcon.animate().rotation(180).setDuration(200).start(); // Rotate up
            }
        });

        cardContentWrapper.addView(headerLayout);
        cardContentWrapper.addView(contentLayout);
        cardView.addView(cardContentWrapper);

        return cardView;
    }

    /**
     * Helper method to add a TextView with common styling to a LinearLayout.
     * @param parentLayout The LinearLayout to add the TextView to.
     * @param text The text content for the TextView.
     */
    private void addTextViewToLayout(LinearLayout parentLayout, String text) {
        TextView textView = new TextView(requireContext());
        textView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        textView.setText(text);
        textView.setTextSize(16f); // sp
        try {
            textView.setTextColor(ContextCompat.getColor(requireContext(), R.color.card_text_color));
        } catch (Exception e) {
            Log.e(TAG, "Error setting content text color, check colors.xml: " + e.getMessage());
            textView.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.black)); // Fallback
        }
        parentLayout.addView(textView);
    }

    // Helper function to convert dp to pixels
    private int dpToPx(int dp) {
        if (getResources() == null) {
            Log.e(TAG, "getResources() is null in dpToPx. Cannot convert DP to PX.");
            return dp;
        }
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
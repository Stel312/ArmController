// --- DataFragment.java ---
package com.example.myapplication.subfragments;

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
    private final List<Object> data;
    private final List<CardView> cardViews = new ArrayList<>(); // List to hold card views;
    // Constructor to pass the list of data
    public DataFragment(List<Object> data) {
        this.data = data;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_data, container, false);

        // Get a reference to the LinearLayout where CardViews will be added
        LinearLayout containerLayout = view.findViewById(R.id.data_card_container_layout);

        // Iterate through the provided data and create a card for each item
        if (data != null) {
            for (Object o : data) {
                CardView card = createCardFromObject(o, 0); // Pass initial depth 0
                if (card != null) {
                    containerLayout.addView(card);
                }
            }
        } else {
            Log.w(TAG, "Data list is null, no cards to display.");
            // Optionally add a message to the UI if no data is provided
            TextView noDataText = new TextView(requireContext());
            noDataText.setText("No data provided to display.");
            noDataText.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16));
            containerLayout.addView(noDataText);
        }

        return view;
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
                1.0f // weight to take up available space
        );
        headerTitleTextView.setLayoutParams(titleLayoutParams);
        headerTitleTextView.setTextSize(18f); // sp
        try {
            headerTitleTextView.setTextColor(ContextCompat.getColor(requireContext(), R.color.header_text_color));
        } catch (Exception e) {
            Log.e(TAG, "Error setting header text color, check colors.xml: " + e.getMessage());
            headerTitleTextView.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.black)); // Fallback
        }

        // Expand/Collapse Arrow Icon
        ImageView arrowIcon = new ImageView(requireContext());
        LinearLayout.LayoutParams arrowLayoutParams = new LinearLayout.LayoutParams(
                32, // width
                32  // height
        );
        arrowIcon.setLayoutParams(arrowLayoutParams);
        arrowIcon.setImageResource(R.drawable.ic_arrow_down); // Ensure this drawable exists
        arrowIcon.setRotation(0); // Initial state: down arrow (collapsed)

        headerLayout.addView(headerTitleTextView);
        headerLayout.addView(arrowIcon);

        // Create the Content Layout (the collapsible part)
        LinearLayout contentLayout = new LinearLayout(requireContext());
        contentLayout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(16));
        contentLayout.setVisibility(View.GONE); // Initially collapsed

        // Populate contentLayout based on object type
        if (o instanceof Vector3f) {
            Vector3f vector = (Vector3f) o;
            headerTitleTextView.setText("Vector3f Data");
            addTextViewToLayout(contentLayout, String.format("X: %.2f", vector.x));
            addTextViewToLayout(contentLayout, String.format("Y: %.2f", vector.y));
            addTextViewToLayout(contentLayout, String.format("Z: %.2f", vector.z));
        } else if (o instanceof Quaternionf) {
            Quaternionf quaternion = (Quaternionf) o;
            headerTitleTextView.setText("Quaternionf Data");
            addTextViewToLayout(contentLayout, String.format("X: %.2f", quaternion.x));
            addTextViewToLayout(contentLayout, String.format("Y: %.2f", quaternion.y));
            addTextViewToLayout(contentLayout, String.format("Z: %.2f", quaternion.z));
            addTextViewToLayout(contentLayout, String.format("W: %.2f", quaternion.w));
        } else if (o instanceof String) {
            String text = (String) o;
            headerTitleTextView.setText("String Data");
            addTextViewToLayout(contentLayout, text);
        } else if (o instanceof List) {
            List<?> innerList = (List<?>) o; // Use wildcard for inner list type
            headerTitleTextView.setText("List (Size: " + innerList.size() + ")");
            // Recursively create cards for each element in the list
            for (Object innerObject : innerList) {
                // Increment depth for nested cards to apply more left margin
                CardView innerCard = createCardFromObject(innerObject, depth + 1);
                if (innerCard != null) {
                    contentLayout.addView(innerCard);
                }
            }
        } else {
            // Handle unsupported data types
            Log.w(TAG, "Unsupported data type for CardView: " + (o != null ? o.getClass().getSimpleName() : "null"));
            headerTitleTextView.setText("Unsupported Data Type");
            addTextViewToLayout(contentLayout, "Cannot display data of type: " + (o != null ? o.getClass().getSimpleName() : "null"));
        }

        // Add header and content to the CardView's wrapper
        cardContentWrapper.addView(headerLayout);
        cardContentWrapper.addView(contentLayout);
        cardView.addView(cardContentWrapper);

        // Set OnClickListener for the header to toggle collapse/expand
        headerLayout.setOnClickListener(v -> {
            if (contentLayout.getVisibility() == View.GONE) {
                contentLayout.setVisibility(View.VISIBLE);
                arrowIcon.animate().rotation(180).setDuration(200).start(); // Rotate up
            } else {
                contentLayout.setVisibility(View.GONE);
                arrowIcon.animate().rotation(0).setDuration(200).start(); // Rotate down
            }
        });

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

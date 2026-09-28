
package com.example.tvremote;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

public final class CastPickerActivity extends Activity {
    private static final int REQ = 9041;
    private String kind = "all";

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        kind = getIntent().getStringExtra("kind");
        if (kind == null) kind = "all";
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if ("photo".equals(kind)) i.setType("image/*");
        else if ("video".equals(kind)) i.setType("video/*");
        else if ("audio".equals(kind)) i.setType("audio/*");
        else {
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*","video/*","audio/*"});
        }
        startActivityForResult(i, REQ);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if (requestCode == REQ) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                try {
                    data.getData().toString();
                    getContentResolver().takePersistableUriPermission(data.getData(),
                            data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch(Exception ignored) {}
                CastBridge.picked(data.getData(),kind);
            }
            finish();
        }
    }
}

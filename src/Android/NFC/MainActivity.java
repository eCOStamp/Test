package Android.NFC;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.NfcManager;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import at.mroland.ndef.UriRecordHelper;
import at.mroland.utils.StringUtils;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONObject;

/**
 * Utilities for URI record encoding.
 */
public class MainActivity extends Activity {

    private static final int PENDING_INTENT_TECH_DISCOVERED = 1;
    private static final int DIALOG_WRITE_URL = 1;
    private static final int DIALOG_WRITE_ERROR = 2;
    private static final int DIALOG_NEW_TAG = 3;
    private static final String ARG_MESSAGE = "message";
    private NfcAdapter mNfcAdapter;
    private EditText mMyUrl;
    private Button mMyWriteUrlButton;
    private boolean mWriteUrl = false;

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        // Retrieve references to the UI components:
        mMyUrl = (EditText) findViewById(R.id.myUrl);
        mMyWriteUrlButton = (Button) findViewById(R.id.myWriteUrlButton);

        // Set action for "Write URL to tag..." button:
        mMyWriteUrlButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // Switch to write mode:
                mWriteUrl = true;

                // Show a dialog when we are ready to write:
                MainActivity.this.showDialog(DIALOG_WRITE_URL);
            }
        });

        // Resolve the intent that started us:
        resolveIntent(this.getIntent(), false);
    }

    /**
     * Called when the activity gets focus.
     */
    @Override
    public void onResume() {
        super.onResume();

        // Retrieve an instance of the NfcAdapter ("connection" to the NFC system service):
        NfcManager nfcManager = (NfcManager) this.getSystemService(Context.NFC_SERVICE);
        if (nfcManager != null) {
            mNfcAdapter = nfcManager.getDefaultAdapter();
        }

        if (mNfcAdapter != null) {
            // The isEnabled() method, if invoked directly after the NFC service
            // crashed, returns false regardless of the real setting (Android 2.3.3+).
            // As a nice side-effect it re-establishes the link to the correct instance
            // of NfcAdapter. Therefore, just execute this method twice whenever we
            // re-request the NfcAdapter, so we can be sure to have a valid handle.
            try {
                mNfcAdapter.isEnabled();
            } catch (NullPointerException e) {
                // Drop NullPointerException that is sometimes thrown
                // when NFC service crashed
            }
            try {
                mNfcAdapter.isEnabled();
            } catch (NullPointerException e) {
                // Drop NullPointerException that is sometimes thrown
                // when NFC service crashed
            }

            // Create a PendingIntent to handle discovery of Ndef and NdefFormatable tags:
            PendingIntent pi = createPendingResult(
                    PENDING_INTENT_TECH_DISCOVERED,
                    new Intent(),
                    0);
            if (pi != null) {
                try {
                    // Enable foreground dispatch for Ndef and NdefFormatable tags:
                    mNfcAdapter.enableForegroundDispatch(
                            this,
                            pi,
                            new IntentFilter[]{
                                new IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
                            },
                            new String[][]{
                                new String[]{"android.nfc.tech.NdefFormatable"},
                                new String[]{"android.nfc.tech.Ndef"}
                            });
                } catch (NullPointerException e) {
                    // Drop NullPointerException that is sometimes thrown
                    // when NFC service crashed
                }
            }
        }
    }

    /**
     * Called when the activity loses focus.
     */
    @Override
    public void onPause() {
        super.onPause();

        if (mNfcAdapter != null) {
            try {
                // Disable foreground dispatch:
                mNfcAdapter.disableForegroundDispatch(this);
            } catch (NullPointerException e) {
                // Drop NullPointerException that is sometimes thrown
                // when NFC service crashed
            }
        }
    }

    /**
     * Called when activity receives a new intent.
     */
    @Override
    public void onNewIntent(Intent data) {
        // Resolve the intent that re-invoked us:
        resolveIntent(data, false);
    }

    /**
     * Called when a pending intent returns (e.g. our foreground dispatch).
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case PENDING_INTENT_TECH_DISCOVERED:
                // Resolve the foreground dispatch intent:
                resolveIntent(data, true);
                break;
        }
    }

    /**
     * Method to handle any intents that triggered our activity.
     * @param data                The intent we received and want to process.
     * @param foregroundDispatch  True if this intent was received through foreground dispatch.
     */
    private void resolveIntent(Intent data, boolean foregroundDispatch) {
        this.setIntent(data);

        String action = data.getAction();

        // We were started from the recent applications history: just show our main activity
        // (otherwise, the last intent that invoked us will be re-processed)
        if ((data.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) { return; }

        // Intent is a tag technology (we are sensitive to Ndef, NdefFormatable) or
        // an NDEF message (we are sensitive to URI records with the URI http://www.mroland.at/)
        if (NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)
                || NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)) {
            // The reference to the tag that invoked us is passed as a parameter (intent extra EXTRA_TAG)
            Tag tag = data.getParcelableExtra(NfcAdapter.EXTRA_TAG);

            if (foregroundDispatch && mWriteUrl) {
                // This is a foreground dispatch and we want to write our URL
                mWriteUrl = false;

                // Get URL from text box:
                String urlStr = mMyUrl.getText().toString();

//                // Convert to URL to byte array (UTF-8 encoded):
//                byte[] urlBytes = urlStr.getBytes(Charset.forName("UTF-8"));
//
//                // Pack the URI into a NDEF URI record payload (consists of a prefix
//                // reduction byte and the remaining URI):
//                byte[] urlPayload = new byte[urlBytes.length + 1];
//                urlPayload[0] = 0;  // no prefix reduction
//                System.arraycopy(urlBytes, 0, urlPayload, 1, urlBytes.length);

                // Use UriRecordHelper to determine an efficient encoding:
                byte[] urlPayload = UriRecordHelper.encodeUriRecordPayload(urlStr);

                // Create a NDEF URI record (NFC Forum well-known type "urn:nfc:wkt:U")
                NdefRecord urlRecord = new NdefRecord(
                        NdefRecord.TNF_WELL_KNOWN, /* TNF: NFC Forum well-known type */
                        NdefRecord.RTD_URI,        /* Type: urn:nfc:wkt:U (short: U) */
                        new byte[0],               /* no ID */
                        urlPayload);

                // Create NDEF message from URI record:
                NdefMessage msg = new NdefMessage(new NdefRecord[]{urlRecord});

                // Write NDEF message to tag:

                // Try to receive an instance of a Ndef tag (preformatted tag)
                // for the current tag object:
                Ndef ndefTag = Ndef.get(tag);
                if (ndefTag != null) {
                    // Our tag is already formatted, we just need to write our message

                    try {
                        // Connect to tag:
                        ndefTag.connect();

                        // Write NDEF message:
                        ndefTag.writeNdefMessage(msg);
                    } catch (Exception e) {
                        Bundle args = new Bundle();
                        args.putString(ARG_MESSAGE, e.toString());
                        showDialog(DIALOG_WRITE_ERROR, args);
                    } finally {
                        // Close connection:
                        try {
                            ndefTag.close();
                        } catch (Exception e) {
                        }
                    }
                } else {
                    // Try to receive an instance of a NdefFormatable tag (unformatted tag,
                    // that can be formatted automatically by the system) for the current
                    // tag object:
                    // WARNING: This tag technology has known problems on Android 4.0.3!
                    NdefFormatable ndefFormatableTag = NdefFormatable.get(tag);
                    if (ndefFormatableTag != null) {
                        // Our tag is not yet formatted, we need to format it with our message

                        try {
                            // Connect to tag:
                            ndefFormatableTag.connect();

                            // Format with NDEF message:
                            ndefFormatableTag.format(msg);
                        } catch (Exception e) {
                            Bundle args = new Bundle();
                            args.putString(ARG_MESSAGE, e.toString());
                            showDialog(DIALOG_WRITE_ERROR, args);
                        } finally {
                            // Close connection:
                            try {
                                ndefFormatableTag.close();
                            } catch (Exception e) {
                            }
                        }
                    } else {
                        // The tag is neither preformatted nor formattable with an NDEF message
                        // by the Android system. In that case we would have to manually
                        // preformat the tag.
                    }
                }

                // Close the write dialog when we completed writing the tag.
                dismissDialog(DIALOG_WRITE_URL);
            } else {
                // Let's read the tag whenever we are not in write mode
              
                // Retrieve information from tag and display it
                StringBuilder tagInfo = new StringBuilder();

                // Get tag's UID:
                byte[] uid = tag.getId();
                //tagInfo.append("Message_ID: ").append(StringUtils.convertByteArrayToHexString(uid)).append("\n\n");

                // Get tag's NDEF messages: The NDEF messages are passed as parameters (intent
                // extra EXTRA_NDEF_MESSAGES) and have to be casted into an NdefMessage array.
                Parcelable[] ndefRaw = data.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
                NdefMessage[] ndefMsgs = null;
                if (ndefRaw != null) {
                    ndefMsgs = new NdefMessage[ndefRaw.length];
                    for (int i = 0; i < ndefMsgs.length; ++i) {
                        // Cast each NDEF message from Parcelable to NdefMessage:
                        ndefMsgs[i] = (NdefMessage) ndefRaw[i];
                    }
                }

                // Find URI records:

                if (ndefMsgs != null) {
                    // Iterate through all NDEF messages on the tag:
                    for (int i = 0; i < ndefMsgs.length; ++i) {
                        // Get NDEF message's records:
                        NdefRecord[] records = ndefMsgs[i].getRecords();

                        if (records != null) {
                            // Iterate through all NDEF records:
                            for (int j = 0; j < records.length; ++j) {
                                // Test if this record is a URI record:
                                if ((records[j].getTnf() == NdefRecord.TNF_WELL_KNOWN)
                                        && Arrays.equals(records[j].getType(), NdefRecord.RTD_URI)) {
                                    // Get record payload:
                                    byte[] payload = records[j].getPayload();
                                    
//                                    // Drop prefix identifier byte and convert remaining URL to string (UTF-8):
//                                    String uri = new String(Arrays.copyOfRange(payload, 1, payload.length), Charset.forName("UTF-8"));

                                    // Use UriRecordHelper to decode URI record payload:
                                    String uri = UriRecordHelper.decodeUriRecordPayload(payload);
                                   
                                    uri=Convert_String(uri);
                                    new HttpAsyncTask().execute(uri);
                                    //uri=POST("http://ecostamp.aosekai.net/api/dummy/",uri);
                                    //tagInfo.append("Message: \"");
                                    //tagInfo.append(uri);
                                    //tagInfo.append("\"\n");
                                    //tagInfo.append("End of Message :)");
                                }
                            }
                        }
                    }
                }

                // Show our tag detected dialog (with the tag information passed as parameter):
                //Bundle args = new Bundle();
                //args.putString(ARG_MESSAGE, tagInfo.toString());
                //showDialog(DIALOG_NEW_TAG, args);
            }
        }
    }

    /**
     * Called when a dialog is created.
     */
    @Override
    protected Dialog onCreateDialog(int id, Bundle args) {
        switch (id) {
            case DIALOG_WRITE_URL:
                // A dialog that we show when we are ready to write to a tag:
                return new AlertDialog.Builder(this)
                        .setTitle("Send Ndef Message")
                        .setMessage("Touch tag to start writing.")
                        .setCancelable(true)
                        .setNeutralButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int arg) {
                                d.cancel();
                            }
                        })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            public void onCancel(DialogInterface d) {
                                mWriteUrl = false;
                            }
                        }).create();

            case DIALOG_WRITE_ERROR:
                // A dialog that we show when the write operation fails:
                return new AlertDialog.Builder(this)
                        .setTitle("Write failed")
                        .setMessage("")
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setCancelable(true)
                        .setNeutralButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int arg) {
                                d.dismiss();
                            }
                        }).create();

            case DIALOG_NEW_TAG:
                // A dialog that we show when we detected and read a tag:
                return new AlertDialog.Builder(this)
                        .setTitle("Tag detected ^_^")
                        .setMessage("")
                        .setCancelable(true)
                        .setNeutralButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int arg) {
                                d.dismiss();
                            }
                        })
                        .create();
        }

        return null;
    }
   
   
    /**
     * Called before a dialog is shown to the user.
     */
    @Override
    protected void onPrepareDialog(int id, Dialog dialog, Bundle args) {
        switch (id) {
            case DIALOG_WRITE_ERROR:
            case DIALOG_NEW_TAG:
                // Pass parameters to the tag detected and the write error dialog:
                if (args != null) {
                    String message = args.getString(ARG_MESSAGE);
                    if (message != null) { ((AlertDialog) dialog).setMessage(message); }
                }
                break;
        }
    }
    
    public boolean isConnected(){
		ConnectivityManager connMgr = (ConnectivityManager) getSystemService(Activity.CONNECTIVITY_SERVICE);
		NetworkInfo networkInfo = connMgr.getActiveNetworkInfo();
		if (networkInfo != null && networkInfo.isConnected())
			return true;
		else
			return false;
	}
    
    private class HttpAsyncTask extends AsyncTask<String, Void, String>{
		@Override
		protected String doInBackground(String...results){
			//person = new Person();
			//person.setName(etName.getText().toString());
			//person.setCountry(etCountry.getText().toString());
			//person.setTwitter(etTwitter.getText().toString());
			
			return POST("http://ecostamp.aosekai.net/api/dummy/",results[0]);
		}
		
		@Override
		protected void onPostExecute(String result){
			//etReply.setText(result);
			Toast.makeText(getBaseContext(), "Data Sent!!! " + result+ "Message End", Toast.LENGTH_LONG).show();
                        StringBuilder tagInfo = new StringBuilder();
                        tagInfo.append("Result: \"");
                        tagInfo.append(result);
                        tagInfo.append("\n");
                        Bundle args = new Bundle();
                        args.putString(ARG_MESSAGE, tagInfo.toString());
                        showDialog(DIALOG_NEW_TAG, args);
		}
	}
    
    public static String POST(String url, String Results){
		InputStream inputStream = null;
		String result = "";
		try{
			HttpClient httpclient = new DefaultHttpClient();
			
			HttpPost httpPost = new HttpPost(url);
			
			String json = "";
			
			JSONObject jsonObject = new JSONObject();
			//jsonObject.accumulate("name", person.getName());
			//jsonObject.accumulate("country", person.getCountry());
			jsonObject.accumulate("Sent", Results);
			
			json = jsonObject.toString();
			
			StringEntity se = new StringEntity(json);
			
			httpPost.setEntity(se);
			
			httpPost.setHeader("Accept","application/json");
			httpPost.setHeader("Content-type","application/json");
			
			HttpResponse httpResponse = httpclient.execute(httpPost);
			
			inputStream = httpResponse.getEntity().getContent();
			
			if(inputStream != null)
				result = convertInputStreamToString(inputStream);
			else
				result = "Did not work!";
			
			
		} catch (Exception e){
                        result = "Exception_Catched!"+ e.getLocalizedMessage();
			Log.d("InputStream",e.getLocalizedMessage());
		}
		
		return result;
	}
    
    private static String convertInputStreamToString(InputStream inputStream) throws IOException{
		BufferedReader bufferedReader = new BufferedReader( new InputStreamReader(inputStream));
		String line = "";
		String result = "";
		while((line = bufferedReader.readLine()) != null)
			result += line;
		inputStream.close();
		return result;
	}
    
    public String Convert_String(String uri)
    {
         if(uri.contains("Deus"))
         {
             uri="CodeName0001";    
         }
         else if(uri.contains("Bevis"))
         {
             uri="CodeName0002";    
         }
         else if(uri.contains("Judy"))
         {
             uri="CodeName0003";    
         }
         else if(uri.contains("Chuo"))
         {
             uri="CodeName0004";    
         }
         else if(uri.contains("Build"))
         {
             uri="CodeName0005";    
         }
         else if(uri.contains("Nachai"))
         {
             uri="CodeName0006";    
         }
         else
         {
             uri="Not Detect Any Known Name";
         }    
        return uri;
    }
}

package net.noratek.smartvoxxwear.service;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;
import com.squareup.picasso.Picasso;
import com.squareup.picasso.Target;

import net.noratek.smartvoxx.common.model.Conference;
import net.noratek.smartvoxx.common.model.Link;
import net.noratek.smartvoxx.common.model.Schedules;
import net.noratek.smartvoxx.common.model.Slot;
import net.noratek.smartvoxx.common.model.SlotList;
import net.noratek.smartvoxx.common.model.Speaker;
import net.noratek.smartvoxx.common.model.Talk;
import net.noratek.smartvoxx.common.utils.Constants;
import net.noratek.smartvoxx.common.utils.Utils;
import net.noratek.smartvoxxwear.R;
import net.noratek.smartvoxxwear.calendar.CalendarHelper;
import net.noratek.smartvoxxwear.rest.service.DevoxxApi;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TreeMap;

import retrofit.Callback;
import retrofit.RestAdapter;
import retrofit.RetrofitError;
import retrofit.client.Response;

/**
 * Created by eloudsa on 28/10/15.
 */
public class WearService extends WearableListenerService {

    private final static String TAG = WearService.class.getCanonicalName();


    // Play services
    private GoogleApiClient mApiClient;

    // Rest
    private RestAdapter mRestAdapter;
    private DevoxxApi mMethods;
    private String mConferenceName;

    @Override
    public void onCreate() {
        super.onCreate();

        // Connect to Play Services
        mApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .build();
        mApiClient.connect();

        // prepare the REST build
        mRestAdapter = new RestAdapter.Builder()
                .setEndpoint(getResources().getString(R.string.devoxx_rest_api))
                .build();

        mMethods = mRestAdapter.create(DevoxxApi.class);
        mConferenceName = getResources().getString(R.string.devoxx_conference);

    }


    @Override
    public void onDestroy() {
        if ((mApiClient != null) && (mApiClient.isConnected())) {
            mApiClient.disconnect();
        }

        super.onDestroy();
    }


    @Override
    public void onMessageReceived(MessageEvent messageEvent) {

        String countryCode = "BE";

        // Processing the incoming message
        String path = messageEvent.getPath();
        String data = new String(messageEvent.getData());


        if (path.equalsIgnoreCase(Constants.CONFERENCES_PATH)) {
            retrieveConferences();
            return;
        }

        if (path.startsWith(Constants.SCHEDULES_PATH)) {
            countryCode = Utils.getLastPartUrl(path);
            retrieveSchedules(countryCode);
            return;
        }

        if (path.startsWith(Constants.SLOTS_PATH)) {
            countryCode = Utils.getLastPartUrl(path);
            retrieveSlots(countryCode, data);
            return;
        }

        if (path.equalsIgnoreCase(Constants.TALK_PATH)) {
            retrieveTalk(data);
            return;
        }

        if (path.equalsIgnoreCase(Constants.SPEAKER_PATH)) {
            retrieveSpeaker(data);
            return;
        }

        if (path.equalsIgnoreCase(Constants.TWITTER_PATH)) {
            followOnTwitter(data);
            return;
        }

        if (path.equalsIgnoreCase(Constants.FAVORITE_PATH)) {
            retrieveFavorite(messageEvent.getData());
            return;
        }

        if (path.equalsIgnoreCase(Constants.ADD_FAVORITE_PATH)) {
            addFavorite(messageEvent.getData());
            return;
        }

        if (path.equalsIgnoreCase(Constants.REMOVE_FAVORITE_PATH)) {
            removeFavorite(messageEvent.getData());
            return;
        }


    }


    //
    // REST Client
    //

    private DevoxxApi getRestClient(String url) {

        // prepare the REST build
        RestAdapter restAdapter = new RestAdapter.Builder()
                .setEndpoint(url)
                .build();
        if (restAdapter == null) {
            return null;
        }

        return restAdapter.create(DevoxxApi.class);
    }



    //
    // Twitter
    //

    // Open the Twitter application or the browser if the app is not installed
    private void followOnTwitter(String inputData) {
        String twitterName = inputData == null ? "" : inputData.trim().toLowerCase();
        twitterName = twitterName.replaceFirst("@", "");

        if (twitterName.isEmpty()) {
            return;
        }

        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://twitter.com/" + twitterName));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        this.startActivity(intent);
    }


    //
    // Favorites
    //


    // Retrieve the favorite from the Calendar
    private void retrieveFavorite(byte[] inputData) {
        DataMap inputMap = DataMap.fromByteArray(inputData);
        if (inputMap == null) {
            return;
        }

        // Retrieve the Talk
        Talk talk = new Talk();
        talk.setId(inputMap.getString("talkId"));
        talk.setTitle(inputMap.getString("title"));
        talk.setFromTimeMillis(inputMap.getLong("fromTimeMillis"));
        talk.setToTimeMillis(inputMap.getLong("toTimeMillis"));

        // Retrieve this talk on the Calendar
        Long eventId = 0L;
        CalendarHelper calendarHelper = new CalendarHelper(getApplicationContext());
        Talk talkEvent = calendarHelper.getTalkByTitleAndTime(talk.getTitle(), talk.getFromTimeMillis(), talk.getToTimeMillis());
        if (talkEvent != null) {
            eventId = talkEvent.getEventId();
        }

        // Send the favorite to the remote device
        sendFavorite(talk.getId(), eventId);
    }

    // Add the favorite on the Calendar
    private void addFavorite(byte[] inputData) {

        DataMap inputMap = DataMap.fromByteArray(inputData);
        if (inputMap == null) {
            return;
        }

        // Retrieve the Talk
        Talk talk = new Talk();
        talk.setId(inputMap.getString("talkId"));
        talk.setTitle(inputMap.getString("title"));
        talk.setSummary(inputMap.getString("summary"));
        talk.setRoomName(inputMap.getString("roomName"));
        talk.setFromTimeMillis(inputMap.getLong("fromTimeMillis"));
        talk.setToTimeMillis(inputMap.getLong("toTimeMillis"));


        // create the calendar's event
        CalendarHelper calendarHelper = new CalendarHelper(getApplicationContext());
        Long eventId = calendarHelper.addEvent(talk);
        if (eventId == null) {
            return;
        }

        // Send the favorite to the remote device
        sendFavorite(talk.getId(), eventId);
    }


    // remove the favorite from the Calendar
    private void removeFavorite(byte[] inputData) {

        DataMap inputMap = DataMap.fromByteArray(inputData);
        if (inputMap == null) {
            return;
        }

        // Retrieve the Talk
        Talk talk = new Talk();
        talk.setId(inputMap.getString("talkId"));
        talk.setEventId(inputMap.getLong("eventId"));

        // remove the favorite from the calendar
        CalendarHelper calendarHelper = new CalendarHelper(getApplicationContext());
        calendarHelper.removeEvent(talk.getEventId());

        // Send the favorite's change to the remote device
        sendFavorite(talk.getId(), 0L);
    }


    // send Favorite to the watch
    private void sendFavorite(String talkId, Long eventId) {

        // send the event
        PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(Constants.FAVORITE_PATH + "/" + talkId);

        // store the data
        DataMap dataMap = new DataMap();
        dataMap.putLong("eventId", eventId);

        // store the event in the datamap to send it to the wear
        putDataMapRequest.getDataMap().putDataMap(Constants.DETAIL_PATH, dataMap);

        if (mApiClient.isConnected()) {
            Wearable.DataApi.putDataItem(mApiClient, putDataMapRequest.asPutDataRequest());
        }

    }

    //
    // Conferences
    //

    // Retrieve the list of Devoxx conferences
    private void retrieveConferences() {

        TreeMap<String, Conference> conferences = getConferences();
        if (conferences == null) {
            return;
        }

        sendConferences(conferences);
    }


    private TreeMap<String, Conference> getConferences() {

        TreeMap<String, Conference> conferences = new TreeMap<>();

        // load list of Devoxx conferences
        String[] stringArray = getResources().getStringArray(R.array.conferences);
        for (String entry : stringArray) {
            String[] splitResult = entry.split("\\|");
            Conference conference = new Conference(splitResult[0], splitResult[1], splitResult[2], splitResult[3]);
            conferences.put(conference.getCountryCode(), conference);
        }

        if (conferences.size() == 0) {
            return null;
        }

        return conferences;
    }

    private Conference getConference(String countryCode) {

        TreeMap<String, Conference> conferences = getConferences();

        // process each conference
        for (String key : conferences.keySet()) {

            if (key.equalsIgnoreCase(countryCode)) {
                return conferences.get(key);
            }
        }

        return null;
    }


    // send Conferences to the watch
    private void sendConferences(TreeMap<String, Conference> conferences) {
        final PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(Constants.CONFERENCES_PATH);

        ArrayList<DataMap> conferencesDataMap = new ArrayList<>();

        // process each conference
        for (String key : conferences.keySet()) {
            Conference conference = conferences.get(key);

            final DataMap conferenceDataMap = new DataMap();

            // process conference's data
            conferenceDataMap.putString("countryCode", conference.getCountryCode());
            conferenceDataMap.putString("title", conference.getTitle());

            conferencesDataMap.add(conferenceDataMap);
        }

        // store the list in the datamap to send it to the watch
        putDataMapRequest.getDataMap().putDataMapArrayList(Constants.LIST_PATH, conferencesDataMap);

        // send the schedules
        if (mApiClient.isConnected()) {
            Wearable.DataApi.putDataItem(mApiClient, putDataMapRequest.asPutDataRequest());
        }
    }


    //
    // Schedules
    //

    // Retrieve schedules from Devoxx
    private void retrieveSchedules(final String countryCode) {

        Conference conference = getConference(countryCode);
        if (conference == null) {
            return;
        }

        DevoxxApi methods = getRestClient(conference.getServerUrl());
        if (methods == null) {
            return;
        }

        // retrieve the schedules list from the server
        Callback callback = new Callback() {
            @Override
            public void success(Object o, Response response) {
                // retrieve schedule from REST
                Schedules scheduleList = (Schedules) o;
                if (scheduleList == null) {
                    Log.d(TAG, "No schedules!");
                    return;
                }

                sendSchedules(countryCode, scheduleList.getLinks());
            }

            @Override
            public void failure(RetrofitError retrofitError) {
                Log.d(TAG, retrofitError.getMessage());
            }
        };
        methods.getSchedules(conference.getName(), callback);
    }


    // send Schedules to the watch
    private void sendSchedules(String countryCode, List<Link> schedules) {
        final PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(Constants.SCHEDULES_PATH + "/" + countryCode);

        ArrayList<DataMap> schedulesDataMap = new ArrayList<>();

        // process each schedule
        for (Link schedule : schedules) {

            final DataMap scheduleDataMap = new DataMap();

            // process and push schedule's data
            scheduleDataMap.putString("day", Utils.getLastPartUrl(schedule.getHref()));
            scheduleDataMap.putString("title", schedule.getTitle());

            schedulesDataMap.add(scheduleDataMap);
        }

        // store the list in the datamap to send it to the watch
        putDataMapRequest.getDataMap().putDataMapArrayList(Constants.LIST_PATH, schedulesDataMap);

        // send the schedules
        if (mApiClient.isConnected()) {
            Wearable.DataApi.putDataItem(mApiClient, putDataMapRequest.asPutDataRequest());
        }
    }


    //
    // Slots
    //


    // Retrieve and Send the slots for a specific schedule.
    private void retrieveSlots(final String countryCode, final String day) {

        Conference conference = getConference(countryCode);
        if (conference == null) {
            return;
        }

        DevoxxApi methods = getRestClient(conference.getServerUrl());
        if (methods == null) {
            return;
        }


        Callback callback = new Callback() {
            @Override
            public void success(Object o, Response response) {
                final SlotList slotList = (SlotList) o;

                if (slotList == null) {
                    return;
                }

                sendSLots(countryCode, slotList.getSlots(), day);
            }

            @Override
            public void failure(RetrofitError retrofitError) {
                Log.d("Main", retrofitError.getMessage());

            }
        };
        methods.getSchedule(conference.getName(), day, callback);
    }


    // Send the schedule's slots to the watch.
    private void sendSLots(String countryCode, List<Slot> slotList, String day) {

        if (slotList == null) {
            return;
        }

        final PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(Constants.SLOTS_PATH + "/" + countryCode + "/" + day);

        ArrayList<DataMap> slotsDataMap = new ArrayList<>();

        for (int index = 0; index < slotList.size(); index++) {

            final DataMap scheduleDataMap = new DataMap();

            final Slot slot = slotList.get(index);

            // process the data
            scheduleDataMap.putString("roomName", slot.getRoomName());
            scheduleDataMap.putLong("fromTimeMillis", slot.getFromTimeMillis());
            scheduleDataMap.putLong("toTimeMillis", slot.getToTimeMillis());

            if (slot.getBreakSession() != null) {
                DataMap breakDataMap = new DataMap();

                //breakDataMap.putString("id", slot.getBreak().getId());
                breakDataMap.putString("nameEN", slot.getBreakSession().getNameEN());
                breakDataMap.putString("nameFR", slot.getBreakSession().getNameFR());

                scheduleDataMap.putDataMap("break", breakDataMap);
            }


            if (slot.getTalk() != null) {
                DataMap talkDataMap = new DataMap();

                talkDataMap.putString("id", slot.getTalk().getId());
                talkDataMap.putLong("eventId", slot.getTalk().getEventId() == null ? 0L : slot.getTalk().getEventId());
                talkDataMap.putString("trackId", slot.getTalk().getTrackId());
                talkDataMap.putString("title", slot.getTalk().getTitle());
                talkDataMap.putString("lang", slot.getTalk().getLang());

                scheduleDataMap.putDataMap("talk", talkDataMap);
            }

            slotsDataMap.add(scheduleDataMap);
        }

        // store the list in the datamap to send it to the wear
        putDataMapRequest.getDataMap().putDataMapArrayList(Constants.LIST_PATH, slotsDataMap);

        // send the slots
        if (mApiClient.isConnected()) {
            Wearable.DataApi.putDataItem(mApiClient, putDataMapRequest.asPutDataRequest());
        }
    }


    //
    // Talk
    //


    // Send the talk to the watch.
    public void retrieveTalk(final String talkId) {

        // retrieve the talk from the server
        Callback callback = new Callback() {
            @Override
            public void success(Object o, Response response) {
                final Talk talk = (Talk) o;

                if (talk == null) {
                    return;
                }

                sendTalk(talk);
            }

            @Override
            public void failure(RetrofitError retrofitError) {
                Log.d("Main", retrofitError.getMessage());

            }
        };
        mMethods.getTalk(mConferenceName, talkId, callback);
    }


    /**
     * Send the talk to the watch.
     */
    private void sendTalk(Talk talk) {
        final PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(Constants.TALK_PATH + "/" + talk.getId());

        final DataMap talkDataMap = new DataMap();

        // process the data
        talkDataMap.putString("timestamp", new Date().toString());
        talkDataMap.putString("id", talk.getId());
        talkDataMap.putLong("eventId", talk.getEventId());
        talkDataMap.putString("talkType", talk.getTalkType());
        talkDataMap.putString("track", talk.getTrack());
        talkDataMap.putString("trackId", talk.getTrackId());
        talkDataMap.putString("title", talk.getTitle());
        talkDataMap.putString("lang", talk.getLang());
        talkDataMap.putString("summary", talk.getSummary());

        ArrayList<DataMap> speakersDataMap = new ArrayList<>();

        // process each speaker's data
        if (talk.getSpeakers() != null) {
            for (int index = 0; index < talk.getSpeakers().size(); index++) {

                final DataMap speakerDataMap = new DataMap();

                final Speaker speaker = talk.getSpeakers().get(index);

                if (speaker.getLink() != null) {
                    // process the data
                    speakerDataMap.putString("uuid", Utils.getLastPartUrl(speaker.getLink().getHref()));
                    speakerDataMap.putString("title", speaker.getLink().getTitle());

                    speakersDataMap.add(speakerDataMap);
                }
            }
        }

        if (speakersDataMap.size() > 0) {
            talkDataMap.putDataMapArrayList(Constants.SPEAKERS_PATH, speakersDataMap);
        }

        // store the list in the datamap to send it to the wear
        putDataMapRequest.getDataMap().putDataMap(Constants.DETAIL_PATH, talkDataMap);

        // send the talk
        if (mApiClient.isConnected()) {
            Wearable.DataApi.putDataItem(mApiClient, putDataMapRequest.asPutDataRequest());
        }
    }


    //
    // Speaker
    //


    // Retrieve and Send the detail of a speaker.
    public void retrieveSpeaker(String speakerId) {

        // retrieve the speaker from the server
        Callback callback = new Callback() {
            @Override
            public void success(Object o, Response response) {
                final Speaker speaker = (Speaker) o;

                if (speaker == null) {
                    return;
                }

                sendSpeaker(speaker);
            }

            @Override
            public void failure(RetrofitError retrofitError) {
                Log.d("Main", retrofitError.getMessage());

            }
        };
        mMethods.getSpeaker(mConferenceName, speakerId, callback);
    }


    // Send the speaker's detail to the watch.
    private void sendSpeaker(final Speaker speaker) {
        PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(Constants.SPEAKER_PATH + "/" + speaker.getUuid());

        final DataMap speakerDataMap = new DataMap();

        // process the data
        speakerDataMap.putString("timestamp", new Date().toString());
        speakerDataMap.putString("uuid", speaker.getUuid());
        speakerDataMap.putString("firstName", speaker.getFirstName());
        speakerDataMap.putString("lastName", speaker.getLastName());
        speakerDataMap.putString("company", speaker.getCompany());
        speakerDataMap.putString("bio", speaker.getBio());
        speakerDataMap.putString("blog", speaker.getBlog());
        speakerDataMap.putString("twitter", speaker.getTwitter());
        speakerDataMap.putString("avatarURL", speaker.getAvatarURL());
        speakerDataMap.putString("avatarImage", speaker.getAvatarImage());

        // store the list in the datamap to send it to the wear
        putDataMapRequest.getDataMap().putDataMap(Constants.DETAIL_PATH, speakerDataMap);

        if (mApiClient.isConnected()) {
            Wearable.DataApi.putDataItem(mApiClient, putDataMapRequest.asPutDataRequest());
        }

        // do we have to retrieve the avatar's image?
        if (((speaker.getAvatarImage() != null) && (speaker.getAvatarImage().isEmpty() == false))) {
            return;
        }

        // retrieve and send speaker's image (if any)
        if (speaker.getAvatarURL() != null) {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    Picasso.with(getApplicationContext())
                            .load(speaker.getAvatarURL())
                            .resize(100, 100)
                            .centerCrop()
                            .into(new Target() {
                                @Override
                                public void onBitmapLoaded(Bitmap bitmap, Picasso.LoadedFrom from) {
                                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream);
                                    byte[] byteArray = byteArrayOutputStream.toByteArray();

                                    String encoded = Base64.encodeToString(byteArray, Base64.DEFAULT);

                                    // update the data map with the avatar
                                    speakerDataMap.putString("avatarImage", encoded);


                                    // as the datamap has changed, a onDataChanged event will be fired on the remote node

                                    // store the list in the datamap to send it to the wear
                                    PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(Constants.SPEAKER_PATH + "/" + speaker.getUuid());
                                    putDataMapRequest.getDataMap().putDataMap(Constants.DETAIL_PATH, speakerDataMap);

                                    // send the speaker
                                    if (mApiClient.isConnected()) {
                                        Wearable.DataApi.putDataItem(mApiClient, putDataMapRequest.asPutDataRequest());
                                    }
                                }

                                @Override
                                public void onBitmapFailed(Drawable errorDrawable) {

                                }

                                @Override
                                public void onPrepareLoad(Drawable placeHolderDrawable) {

                                }
                            });
                }
            });

        }
    }


}

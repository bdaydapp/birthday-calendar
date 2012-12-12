/*
 * Copyright (C) 2012 Dominik Schürmann <dominik@dominikschuermann.de>
 * Copyright (C) 2010 Sam Steele
 *
 * This file is part of Birthday Adapter.
 * 
 * Birthday Adapter is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Birthday Adapter is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Birthday Adapter.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.birthdayadapter.service;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import org.birthdayadapter.R;
import org.birthdayadapter.util.Constants;
import org.birthdayadapter.util.Log;
import org.birthdayadapter.util.PreferencesHelper;

import android.accounts.Account;
import android.accounts.OperationCanceledException;
import android.annotation.SuppressLint;
import android.app.Service;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SyncResult;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.BaseColumns;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Events;
import android.provider.CalendarContract.Reminders;
import android.provider.ContactsContract;

@SuppressLint("NewApi")
public class CalendarSyncAdapterService extends Service {
    private static SyncAdapterImpl sSyncAdapter = null;

    private static String CALENDAR_COLUMN_NAME = "birthday_adapter";

    public CalendarSyncAdapterService() {
        super();
    }

    private static class SyncAdapterImpl extends AbstractThreadedSyncAdapter {
        private Context mContext;

        public SyncAdapterImpl(Context context) {
            super(context, true);
            mContext = context;
        }

        @Override
        public void onPerformSync(Account account, Bundle extras, String authority,
                ContentProviderClient provider, SyncResult syncResult) {
            try {
                CalendarSyncAdapterService.performSync(mContext, account, extras, authority,
                        provider, syncResult);
            } catch (OperationCanceledException e) {
                Log.e(Constants.TAG, "OperationCanceledException", e);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        IBinder ret = null;
        ret = getSyncAdapter().getSyncAdapterBinder();
        return ret;
    }

    private SyncAdapterImpl getSyncAdapter() {
        if (sSyncAdapter == null)
            sSyncAdapter = new SyncAdapterImpl(this);
        return sSyncAdapter;
    }

    /**
     * Builds URI for Birthday Adapter based on account. Ensures that only the calendar of Birthday
     * Adapter is chosen.
     * 
     * @param uri
     * @return
     */
    public static Uri getBirthdayAdapterUri(Uri uri) {
        return uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(Calendars.ACCOUNT_NAME, Constants.ACCOUNT_NAME)
                .appendQueryParameter(Calendars.ACCOUNT_TYPE, Constants.ACCOUNT_TYPE).build();
    }

    /**
     * Updates calendar color
     * 
     * @param context
     * @param color
     */
    public static void updateCalendarColor(Context context, int color) {
        ContentResolver contentResolver = context.getContentResolver();

        Uri uri = ContentUris.withAppendedId(getBirthdayAdapterUri(Calendars.CONTENT_URI),
                getCalendar(context));

        Log.d(Constants.TAG, "Updating calendar color to " + color + " with uri " + uri.toString());

        ContentProviderClient client = contentResolver
                .acquireContentProviderClient(CalendarContract.AUTHORITY);

        ContentValues values = new ContentValues();
        values.put(Calendars.CALENDAR_COLOR, color);
        try {
            client.update(uri, values, null, null);
        } catch (RemoteException e) {
            Log.e(Constants.TAG, "Error while updating calendar color!");
            e.printStackTrace();
        }
        client.release();
    }

    /**
     * Gets calendar id, when no calendar is present, create one!
     * 
     * @param context
     * @return
     */
    private static long getCalendar(Context context) {
        Log.d(Constants.TAG, "getCalendar Method...");

        ContentResolver contentResolver = context.getContentResolver();

        // Find the calendar if we've got one
        Uri calenderUri = getBirthdayAdapterUri(Calendars.CONTENT_URI);

        // be sure to select the birthday calendar only (additionally to appendQueries in
        // getBirthdayAdapterUri for Android < 4)
        Cursor c1 = contentResolver.query(calenderUri, new String[] { BaseColumns._ID },
                Calendars.ACCOUNT_NAME + " = ? AND " + Calendars.ACCOUNT_TYPE + " = ?",
                new String[] { Constants.ACCOUNT_NAME, Constants.ACCOUNT_TYPE }, null);

        try {
            if (c1.moveToNext()) {
                return c1.getLong(0);
            } else {
                ArrayList<ContentProviderOperation> operationList = new ArrayList<ContentProviderOperation>();

                ContentProviderOperation.Builder builder = ContentProviderOperation
                        .newInsert(calenderUri);
                builder.withValue(Calendars.ACCOUNT_NAME, Constants.ACCOUNT_NAME);
                builder.withValue(Calendars.ACCOUNT_TYPE, Constants.ACCOUNT_TYPE);
                builder.withValue(Calendars.NAME, CALENDAR_COLUMN_NAME);
                builder.withValue(Calendars.CALENDAR_DISPLAY_NAME,
                        context.getString(R.string.calendar_display_name));
                builder.withValue(Calendars.CALENDAR_COLOR, PreferencesHelper.getColor(context));
                builder.withValue(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_READ);
                builder.withValue(Calendars.OWNER_ACCOUNT, Constants.ACCOUNT_NAME);
                builder.withValue(Calendars.SYNC_EVENTS, 1);
                builder.withValue(Calendars.VISIBLE, 1);
                operationList.add(builder.build());
                try {
                    contentResolver.applyBatch(CalendarContract.AUTHORITY, operationList);
                } catch (Exception e) {
                    Log.e(Constants.TAG, "Error: " + e.getMessage());
                    e.printStackTrace();
                    return -1;
                }
                return getCalendar(context);
            }
        } finally {
            c1.close();
        }
    }

    /**
     * Delete all reminders of birthday adapter by going through all events and delete corresponding
     * reminders. This is needed as ContentResolver can not join directly.
     * 
     * @param context
     */
    private static void deleteAllReminders(Context context) {
        Log.d(Constants.TAG, "Going through all events and deleting all reminders...");

        ContentResolver contentResolver = context.getContentResolver();

        // get cursor for all events
        Cursor eventsCursor = contentResolver.query(getBirthdayAdapterUri(Events.CONTENT_URI),
                new String[] { Events._ID }, Events.CALENDAR_ID + "= ?",
                new String[] { String.valueOf(getCalendar(context)) }, null);
        int eventIdColumn = eventsCursor.getColumnIndex(Events._ID);

        ArrayList<ContentProviderOperation> operationList = new ArrayList<ContentProviderOperation>();

        Uri remindersUri = getBirthdayAdapterUri(Reminders.CONTENT_URI);

        ContentProviderOperation.Builder builder = null;

        // go through all events
        try {
            while (eventsCursor.moveToNext()) {
                long eventId = eventsCursor.getLong(eventIdColumn);

                Log.d(Constants.TAG, "Delete reminders for event id: " + eventId);

                // get all reminders for this specific event
                Cursor remindersCursor = contentResolver.query(remindersUri, new String[] {
                        Reminders._ID, Reminders.MINUTES }, Reminders.EVENT_ID + "= ?",
                        new String[] { String.valueOf(eventId) }, null);
                int remindersIdColumn = remindersCursor.getColumnIndex(Reminders._ID);

                /* Delete reminders for this event */
                try {
                    while (remindersCursor.moveToNext()) {
                        long currentReminderId = remindersCursor.getLong(remindersIdColumn);
                        Uri currentReminderUri = ContentUris.withAppendedId(remindersUri,
                                currentReminderId);

                        builder = ContentProviderOperation.newDelete(currentReminderUri);

                        // add operation to list, later executed
                        if (builder != null) {
                            operationList.add(builder.build());
                        }
                    }
                } finally {
                    remindersCursor.close();
                }
            }
        } finally {
            eventsCursor.close();
        }

        try {
            contentResolver.applyBatch(CalendarContract.AUTHORITY, operationList);
        } catch (Exception e) {
            Log.e(Constants.TAG, "Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Get all reminder minutes from preferences as int array
     * 
     * @param context
     * @return
     */
    private static int[] getReminderMinutes(Context context) {
        // get all reminders
        int[] minutes = new int[3];
        for (int i = 0; i < 3; i++) {
            minutes[i] = PreferencesHelper.getReminder(context, i);
        }

        return minutes;
    }

    /**
     * Set new minutes to all reminders in birthday calendar. newMinutes=-1 will delete all
     * reminders!
     * 
     * @param context
     */
    public static void updateAllReminders(Context context, int reminderNo, int newMinutes) {
        // before adding reminders, delete all existing ones
        deleteAllReminders(context);

        ContentResolver contentResolver = context.getContentResolver();

        // get all reminder minutes from prefs
        int[] minutes = getReminderMinutes(context);
        // override reminder with new value from preference
        minutes[reminderNo] = newMinutes;

        // get cursor for all events
        String[] eventsProjection = new String[] { Events._ID };
        String eventsWhere = Events.CALENDAR_ID + " = ?";
        String[] eventsSelectionArgs = new String[] { String.valueOf(getCalendar(context)) };
        Cursor eventsCursor = contentResolver.query(getBirthdayAdapterUri(Events.CONTENT_URI),
                eventsProjection, eventsWhere, eventsSelectionArgs, null);
        int eventIdColumn = eventsCursor.getColumnIndex(Events._ID);

        ArrayList<ContentProviderOperation> operationList = new ArrayList<ContentProviderOperation>();

        Uri remindersUri = getBirthdayAdapterUri(Reminders.CONTENT_URI);

        ContentProviderOperation.Builder builder = null;

        // go through all events
        try {
            while (eventsCursor.moveToNext()) {
                long eventId = eventsCursor.getLong(eventIdColumn);

                Log.d(Constants.TAG, "Insert reminders for event id: " + eventId);

                for (int i = 0; i < 3; i++) {
                    if (minutes[i] != Constants.DISABLED_REMINDER) {
                        Log.d(Constants.TAG,
                                "Create new reminder with uri " + remindersUri.toString());
                        builder = ContentProviderOperation.newInsert(remindersUri);
                        builder.withValue(Reminders.EVENT_ID, eventId);
                        builder.withValue(Reminders.MINUTES, minutes[i]);

                        // add operation to list, later executed
                        operationList.add(builder.build());
                    }
                }

                /*
                 * intermediate commit - otherwise the binder transaction fails on large
                 * operationList
                 */
                if (operationList.size() > 200) {
                    try {
                        Log.d(Constants.TAG, "Start applying the batch...");
                        contentResolver.applyBatch(CalendarContract.AUTHORITY, operationList);
                        Log.d(Constants.TAG, "Applying the batch was successful!");
                        operationList.clear();
                    } catch (Exception e) {
                        Log.e(Constants.TAG, "Applying batch error!", e);
                    }
                }
            }
        } finally {
            eventsCursor.close();
        }

        /* Create reminders */
        if (operationList.size() > 0) {
            try {
                Log.d(Constants.TAG, "Start applying the batch...");
                contentResolver.applyBatch(CalendarContract.AUTHORITY, operationList);
                Log.d(Constants.TAG, "Applying the batch was successful!");
            } catch (Exception e) {
                Log.e(Constants.TAG, "Applying batch error!", e);
            }
        }

    }

    /**
     * Get a new ContentProviderOperation to insert a event
     * 
     * @param context
     * @param calendarId
     * @param eventDate
     * @param year
     *            The event is inserted for this year
     * @param title
     * @return
     */
    private static ContentProviderOperation insertEvent(Context context, long calendarId,
            Date eventDate, int year, String title) {
        ContentProviderOperation.Builder builder;

        builder = ContentProviderOperation.newInsert(getBirthdayAdapterUri(Events.CONTENT_URI));

        Calendar cal = Calendar.getInstance();
        cal.setTime(eventDate);
        cal.set(Calendar.YEAR, year);
        cal.set(Calendar.HOUR, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        // allday events have to be set in UTC!
        // without UTC it results in:
        // CalendarProvider2 W insertInTransaction: allDay is true but sec, min, hour were not 0.
        // http://stackoverflow.com/questions/3440172/getting-exception-when-inserting-events-in-android-calendar
        cal.setTimeZone(TimeZone.getTimeZone("UTC"));

        long dtstart = cal.getTimeInMillis();

        builder.withValue(Events.CALENDAR_ID, calendarId);
        builder.withValue(Events.DTSTART, dtstart);
        builder.withValue(Events.DTEND, dtstart);
        builder.withValue(Events.TITLE, title);
        builder.withValue(Events.ALL_DAY, 1);

        builder.withValue(Events.STATUS, Events.STATUS_CONFIRMED);
        return builder.build();
    }

    /**
     * The date format in the contact events is not standardized! See
     * http://dmfs.org/carddav/?date_format . This method will try to parse it by first using
     * yyyy-MM-dd, --MM-dd (no year specified), yyyyMMdd, and then timestamp.
     * 
     * @param eventDateString
     * @return eventDate as Date object
     */
    private static Date parseEventDateString(String eventDateString) {
        SimpleDateFormat dateFormat1 = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        dateFormat1.setTimeZone(TimeZone.getDefault());
        SimpleDateFormat dateFormat2 = new SimpleDateFormat("--MM-dd", Locale.US);
        dateFormat2.setTimeZone(TimeZone.getDefault());
        SimpleDateFormat dateFormat3 = new SimpleDateFormat("yyyyMMdd", Locale.US);
        dateFormat3.setTimeZone(TimeZone.getDefault());

        Date eventDate = null;

        try {
            eventDate = dateFormat1.parse(eventDateString);

        } catch (ParseException e) {
            Log.d(Constants.TAG, "Event Date String " + eventDateString
                    + " could not be parsed with yyyy-MM-dd! Falling back to --MM-dd!");

            try {
                eventDate = dateFormat2.parse(eventDateString);

                // Because no year is defined in address book, set year to 1700
                // When year < 1800 it is not displayed in brackets
                Calendar cal = Calendar.getInstance();
                cal.setTime(eventDate);
                cal.set(Calendar.YEAR, 1700);
                eventDate = cal.getTime();

            } catch (ParseException e2) {
                Log.d(Constants.TAG, "Event Date String " + eventDateString
                        + " could not be parsed with --MM-dd! Falling back to yyyyMMdd!");
                try {
                    eventDate = dateFormat3.parse(eventDateString);

                } catch (ParseException e3) {
                    Log.d(Constants.TAG, "Event Date String " + eventDateString
                            + " could not be parsed with yyyyMMdd! Falling back to timestamp!");
                    try {
                        eventDate = new Date(Long.parseLong(eventDateString));

                    } catch (NumberFormatException e4) {
                        Log.e(Constants.TAG, "Event Date String " + eventDateString
                                + " could not be parsed as a timestamp! Parsing failed!");

                        eventDate = null;
                    }
                }
            }
        }

        Log.d(Constants.TAG,
                "Event Date String " + eventDateString + " was parsed as " + eventDate.toString());

        return eventDate;
    }

    /**
     * Get Cursor with name, contact id, date of event, and type columns
     * 
     * http://stackoverflow.com/questions/8579883/get-birthday-for-each-contact-in-android-
     * application
     * 
     * @return
     */
    private static Cursor getContactsEvents(ContentResolver contentResolver) {
        Uri uri = ContactsContract.Data.CONTENT_URI;

        String[] projection = new String[] { ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Event.CONTACT_ID,
                ContactsContract.CommonDataKinds.Event.START_DATE,
                ContactsContract.CommonDataKinds.Event.TYPE,
                ContactsContract.CommonDataKinds.Event.LABEL };

        String where = ContactsContract.Data.MIMETYPE + "= ? AND "
                + ContactsContract.CommonDataKinds.Event.TYPE + " IS NOT NULL";
        String[] selectionArgs = new String[] { ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE };
        String sortOrder = null;

        return contentResolver.query(uri, projection, where, selectionArgs, sortOrder);
    }

    /**
     * Generates title for events
     * 
     * @param context
     * @param eventType
     * @param cursor
     * @param eventCustomLabelColumn
     * @param includeAge
     * @param displayName
     * @param age
     * @return
     */
    private static String generateTitle(Context context, int eventType, Cursor cursor,
            int eventCustomLabelColumn, boolean includeAge, String displayName, int age) {
        String title = null;
        switch (eventType) {
        case ContactsContract.CommonDataKinds.Event.TYPE_CUSTOM:
            String eventCustomLabel = cursor.getString(eventCustomLabelColumn);

            if (includeAge) {
                title = String.format(context.getString(R.string.event_title_custom_with_age),
                        displayName, eventCustomLabel, age);
            } else {
                title = String.format(context.getString(R.string.event_title_custom_without_age),
                        displayName, eventCustomLabel);
            }
            break;
        case ContactsContract.CommonDataKinds.Event.TYPE_ANNIVERSARY:
            if (includeAge) {
                title = String.format(context.getString(R.string.event_title_anniversary_with_age),
                        displayName, age);
            } else {
                title = String.format(
                        context.getString(R.string.event_title_anniversary_without_age),
                        displayName);
            }
            break;
        case ContactsContract.CommonDataKinds.Event.TYPE_OTHER:
            if (includeAge) {
                title = String.format(context.getString(R.string.event_title_other_with_age),
                        displayName, age);
            } else {
                title = String.format(context.getString(R.string.event_title_other_without_age),
                        displayName);
            }
            break;
        case ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY:
            if (includeAge) {
                title = String.format(context.getString(R.string.event_title_birthday_with_age),
                        displayName, age);
            } else {
                title = String.format(context.getString(R.string.event_title_birthday_without_age),
                        displayName);
            }
            break;
        default:
            if (includeAge) {
                title = String.format(context.getString(R.string.event_title_other_with_age),
                        displayName, age);
            } else {
                title = String.format(context.getString(R.string.event_title_other_without_age),
                        displayName);
            }
            break;
        }

        return title;
    }

    private static void performSync(Context context, Account account, Bundle extras,
            String authority, ContentProviderClient provider, SyncResult syncResult)
            throws OperationCanceledException {
        performSync(context);
    }

    public static void performSync(Context context) {
        Log.d(Constants.TAG, "Starting sync...");

        ContentResolver contentResolver = context.getContentResolver();

        if (contentResolver == null) {
            Log.e(Constants.TAG, "Unable to get content resolver!");
            return;
        }

        long calendarId = getCalendar(context);
        if (calendarId == -1) {
            Log.e("CalendarSyncAdapter", "Unable to create calendar");
            return;
        }

        // Okay, now this works as follows:
        // 1. Clear events table for this account completely
        // 2. Get birthdays from contacts
        // 3. Create events for each birthday

        // Known limitations:
        // - I am not doing any updating, just delete everything and then recreate everything
        // - birthdays may be stored in other ways on some phones
        // see
        // http://stackoverflow.com/questions/8579883/get-birthday-for-each-contact-in-android-application

        // empty table
        // with additional selection of calendar id, necessary on Android < 4 to remove events only
        // from birthday calendar
        int delEventsRows = contentResolver.delete(getBirthdayAdapterUri(Events.CONTENT_URI),
                Events.CALENDAR_ID + " = ?", new String[] { String.valueOf(calendarId) });
        Log.i(Constants.TAG, "Events of birthday calendar is now empty, deleted " + delEventsRows
                + " rows!");
        Log.i(Constants.TAG, "Reminders of birthday calendar is now empty!");

        int[] reminderMinutes = getReminderMinutes(context);

        // collection of birthdays that will later be added to the calendar
        ArrayList<ContentProviderOperation> operationList = new ArrayList<ContentProviderOperation>();

        // iterate through all Contact Events
        Cursor cursor = getContactsEvents(contentResolver);

        if (cursor == null) {
            Log.e(Constants.TAG, "Unable to get events from contacts! Cursor returns null!");
            return;
        }

        try {
            int eventDateColumn = cursor
                    .getColumnIndex(ContactsContract.CommonDataKinds.Event.START_DATE);
            int displayNameColumn = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME);
            int eventTypeColumn = cursor
                    .getColumnIndex(ContactsContract.CommonDataKinds.Event.TYPE);
            int eventCustomLabelColumn = cursor
                    .getColumnIndex(ContactsContract.CommonDataKinds.Event.LABEL);

            int backRef = 0;
            while (cursor.moveToNext()) {
                String eventDateString = cursor.getString(eventDateColumn);
                String displayName = cursor.getString(displayNameColumn);
                int eventType = cursor.getInt(eventTypeColumn);

                Date eventDate = parseEventDateString(eventDateString);

                // only proceed when parsing didn't fail
                if (eventDate != null) {

                    // get year from event
                    Calendar eventCal = Calendar.getInstance();
                    eventCal.setTime(eventDate);
                    int eventYear = eventCal.get(Calendar.YEAR);
                    Log.d(Constants.TAG, "Event Year: " + eventYear);

                    /*
                     * If year < 1800 don't show brackets with age behind name.
                     * 
                     * When no year is defined parseEventDateString() sets it to 1700
                     * 
                     * Also iCloud for example sets year to 1604 if no year is defined in their user
                     * interface
                     */
                    boolean hasYear = false;
                    if (eventYear >= 1800) {
                        hasYear = true;
                    }

                    // get current year
                    Calendar currCal = Calendar.getInstance();
                    int currYear = currCal.get(Calendar.YEAR);

                    /*
                     * Insert events for the past 3 years and the next 5 years.
                     * 
                     * Events are not inserted as recurring events to have different titles with
                     * birthday age in it.
                     */
                    int startYear = currYear - 3;
                    int endYear = currYear + 5;

                    for (int iteratedYear = startYear; iteratedYear <= endYear; iteratedYear++) {
                        Log.d(Constants.TAG, "iteratedYear: " + iteratedYear);

                        // calculate age
                        int age = iteratedYear - eventYear;

                        // if birthday has year and age of this event >= 0, display age in title
                        boolean includeAge = false;
                        if (hasYear && age >= 0) {
                            includeAge = true;
                        }

                        String title = generateTitle(context, eventType, cursor,
                                eventCustomLabelColumn, includeAge, displayName, age);
                        Log.d(Constants.TAG, "Title: " + title);

                        Log.d(Constants.TAG, "BackRef is " + backRef);

                        operationList.add(insertEvent(context, calendarId, eventDate, iteratedYear,
                                title));

                        /*
                         * Gets ContentProviderOperation to insert new reminder to the
                         * ContentProviderOperation with the given backRef. This is done using
                         * "withValueBackReference"
                         */
                        int noOfReminderOperations = 0;
                        for (int i = 0; i < 3; i++) {
                            if (reminderMinutes[i] != Constants.DISABLED_REMINDER) {
                                ContentProviderOperation.Builder builder = ContentProviderOperation
                                        .newInsert(getBirthdayAdapterUri(Reminders.CONTENT_URI));

                                // add reminder to last added event identified by backRef
                                // see
                                // http://stackoverflow.com/questions/4655291/semantics-of-withvaluebackreference
                                builder.withValueBackReference(Reminders.EVENT_ID, backRef);
                                builder.withValue(Reminders.MINUTES, reminderMinutes[i]);
                                builder.withValue(Reminders.METHOD, Reminders.METHOD_ALERT);
                                operationList.add(builder.build());

                                noOfReminderOperations += 1;
                            }
                        }

                        // for the next...
                        backRef += 1 + noOfReminderOperations;

                        /*
                         * intermediate commit - otherwise the binder transaction fails on large
                         * operationList
                         */
                        if (operationList.size() > 200) {
                            try {
                                Log.d(Constants.TAG, "Start applying the batch...");
                                contentResolver.applyBatch(CalendarContract.AUTHORITY,
                                        operationList);
                                Log.d(Constants.TAG, "Applying the batch was successful!");
                                backRef = 0;
                                operationList.clear();
                            } catch (Exception e) {
                                Log.e(Constants.TAG, "Applying batch error!", e);
                            }
                        }
                    }
                }
            }
        } finally {
            cursor.close();
        }

        /* Create events */
        if (operationList.size() > 0) {
            try {
                Log.d(Constants.TAG, "Start applying the batch...");
                contentResolver.applyBatch(CalendarContract.AUTHORITY, operationList);
                Log.d(Constants.TAG, "Applying the batch was successful!");
            } catch (Exception e) {
                Log.e(Constants.TAG, "Applying batch error!", e);
            }
        }
    }
}
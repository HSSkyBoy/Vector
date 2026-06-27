package com.android.internal.util;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import java.io.IOException;
import java.io.InputStream;

public class XmlUtils {
    public static void beginDocument(XmlPullParser parser, String firstElementName)
            throws XmlPullParserException, IOException {
        throw new UnsupportedOperationException("STUB");
    }

    public static String readText(XmlPullParser parser) throws IOException, XmlPullParserException {
        throw new UnsupportedOperationException("STUB");
    }

    public static int nextElement(XmlPullParser parser) throws XmlPullParserException, IOException {
        throw new UnsupportedOperationException("STUB");
    }

    public static int readIntAttribute(XmlPullParser in, String name, int defaultValue) {
        throw new UnsupportedOperationException("STUB");
    }

    public static boolean readBooleanAttribute(XmlPullParser in, String name) {
        throw new UnsupportedOperationException("STUB");
    }

    public static String readStringAttribute(XmlPullParser in, String name) {
        throw new UnsupportedOperationException("STUB");
    }

    public static void skipCurrentTag(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        throw new UnsupportedOperationException("STUB");
    }

    public static void readListMap(XmlPullParser parser, java.util.List<java.util.Map<String, String>> list,
            String[] tag, String[] key, String[] value) throws XmlPullParserException, IOException {
        throw new UnsupportedOperationException("STUB");
    }
}

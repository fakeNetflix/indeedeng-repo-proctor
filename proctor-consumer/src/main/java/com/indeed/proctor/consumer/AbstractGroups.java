package com.indeed.proctor.consumer;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.indeed.proctor.common.ProctorResult;
import com.indeed.proctor.common.model.Allocation;
import com.indeed.proctor.common.model.ConsumableTestDefinition;
import com.indeed.proctor.common.model.Payload;
import com.indeed.proctor.common.model.TestBucket;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

public abstract class AbstractGroups {
    private final ProctorResult proctorResult;
    private final LinkedHashMap<String, TestBucket> buckets;

    /**
     * A character to separate allocation id and test name for a formatted test group with an allocation.
     */
    protected static final char ALLOCATION_GROUP_SEPARATOR = ':';

    protected AbstractGroups(final ProctorResult proctorResult) {
        this.proctorResult = proctorResult;
        this.buckets = Maps.newLinkedHashMap();
        for (final Entry<String, TestBucket> entry : proctorResult.getBuckets().entrySet()) {
            final TestBucket testBucket = entry.getValue();
            this.buckets.put(entry.getKey(), testBucket);
        }
    }

    public Map<String, String> getTestVersions() {
        return proctorResult.getTestVersions();
    }

    /**
     * @param testName test name
     * @param value bucket value
     * @return true if bucket is active
     * @deprecated Use {@link #isBucketActive(String, int, int)} instead
     */
    protected boolean isBucketActive(final String testName, final int value) {
        final TestBucket testBucket = buckets.get(testName);
        return ((testBucket != null) && (value == testBucket.getValue()));
    }

    protected boolean isBucketActive(final String testName, final int value, final int defaultValue) {
        final TestBucket testBucket = buckets.get(testName);
        if (null == testBucket) {
            return value == defaultValue;
        } else {
            return value == testBucket.getValue();
        }
    }

    protected int getValue(final String testName, final int defaultValue) {
        final TestBucket testBucket = buckets.get(testName);
        if (testBucket == null) {
            return defaultValue;
        }
        return testBucket.getValue();
    }

    public Map<String, String> getTestVersions(final Set<String> tests) {
        final Map<String, String> selectedTestVersions = Maps.newLinkedHashMap();
        final Map<String, ConsumableTestDefinition> testDefinitions = proctorResult.getTestDefinitions();
        for (final String testName : tests) {
            final ConsumableTestDefinition testDefinition = testDefinitions.get(testName);
            if (testDefinition != null) {
                selectedTestVersions.put(testName, testDefinition.getVersion());
            }
        }
        return selectedTestVersions;
    }

    /**
     * Return the Payload attached to the current active bucket for |test|.
     * Always returns a payload so the client doesn't crash on a malformed
     * test definition.
     *
     * @param testName test name
     * @return pay load attached to the current active bucket
     * @deprecated Use {@link #getPayload(String, Bucket)} instead
     */
    @Nonnull
    protected Payload getPayload(final String testName) {
        // Get the current bucket.
        final TestBucket testBucket = buckets.get(testName);

        // Lookup Payloads for this test
        if (testBucket != null) {
            final Payload payload = testBucket.getPayload();
            if (null != payload) {
                return payload;
            }
        }

        return Payload.EMPTY_PAYLOAD;
    }

    @Nonnull
    protected Payload getPayload(final String testName, @Nonnull final Bucket<?> fallbackBucket) {
        // Get the current bucket.
        final TestBucket testBucket = buckets.get(testName);

        // Lookup Payloads for this test
        @Nullable final Payload payload;
        if (testBucket != null) {
            payload = testBucket.getPayload();

        } else {
            final TestBucket fallbackTestBucket = getTestBucketForBucket(testName, fallbackBucket);

            if (null != fallbackTestBucket) {
                payload = fallbackTestBucket.getPayload();

            } else {
                payload = null;
            }
        }

        return payload != null ? payload : Payload.EMPTY_PAYLOAD;
    }

    /**
     * Return the TestBucket, as defined in the current test matrix, for the test called testName with bucket value targetBucket.getValue().
     * Can return null if it can't find any such bucket.
     * This does a linear search over the list of defined buckets.  There shouldn't be too many buckets in any test,
     * so this should be fast enough.
     *
     * @param testName     test name
     * @param targetBucket target bucket
     * @return the TestBucket. Return null if not found.
     */
    @Nullable
    protected TestBucket getTestBucketForBucket(final String testName, final Bucket<?> targetBucket) {
        final @Nullable Map<String, ConsumableTestDefinition> testDefinitions = proctorResult.getTestDefinitions();
        if (testDefinitions != null) {
            final @Nullable ConsumableTestDefinition testDefinition = testDefinitions.get(testName);
            if (testDefinition != null) {
                final @Nullable List<TestBucket> buckets = testDefinition.getBuckets();
                if (buckets != null) {
                    for (final TestBucket testBucket : buckets) {
                        if (targetBucket.getValue() == testBucket.getValue()) {
                            return testBucket;
                        }
                    }
                }
            }
        }
        return null;
    }

    public String toLongString() {
        if (proctorResult.getBuckets().isEmpty()) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        for (final Entry<String, TestBucket> entry : proctorResult.getBuckets().entrySet()) {
            final String testName = entry.getKey();
            final TestBucket testBucket = entry.getValue();
            sb.append(testName).append('-').append(testBucket.getName()).append(',');
        }
        return sb.deleteCharAt(sb.length() - 1)
                .toString();
    }

    /**
     * To be called when logging ONLY
     * @return formatted group string
     */
    @Override
    public String toString() {
        if (isEmpty()) {
            return "";
        }
        final StringBuilder sb = buildTestGroupString();
        if (sb.length() == 0) {
            return "";
        }
        sb.deleteCharAt(sb.length() - 1);
        return sb.toString();
    }

    public StringBuilder buildTestGroupString() {
        final StringBuilder sb = new StringBuilder();
        appendTestGroups(sb);
        return sb;
    }

    public void appendTestGroups(final StringBuilder sb) {
        appendTestGroups(sb, ',');
    }

    /**
     * Return a value indicating if the groups are empty
     * and should be represented by an empty-string
     * {@link #toString()}
     * @return true if empty
     */
    protected boolean isEmpty() {
        return proctorResult.getBuckets().isEmpty();
    }

    /**
     * Appends each test group in two forms to the StringBuilder using the separator to delimit them.
     * The two forms are [test name + bucket value] and [allocation id + ":" + test name + bucket value].
     * For example, it appends "bgcolortst1,#A1:bgcolortst1" if test name is bgcolortst and allocation id is #A1 and separator is ",".
     * If a test is silent or its bucket value is negative, it is skipped to append.
     * the separator should be appended for each test group added to the string builder
     * {@link #toString()}
     * {@link #buildTestGroupString()} or {@link #appendTestGroups(StringBuilder)}
     *
     * @param sb        a string builder
     * @param separator a char used as separator
     */
    public void appendTestGroups(final StringBuilder sb, final char separator) {
        final List<String> testNames = getLoggingTestNames();
        appendTestGroupsWithoutAllocations(sb, separator, testNames);
        appendTestGroupsWithAllocations(sb, separator, testNames);
    }

    /**
     * Returns test names to format test groups of them for logging purpose.
     */
    protected final List<String> getLoggingTestNames() {
        final Map<String, ConsumableTestDefinition> testDefinitions = proctorResult.getTestDefinitions();
        final ImmutableList.Builder<String> builder = ImmutableList.builder();
        for (final Entry<String, TestBucket> entry : proctorResult.getBuckets().entrySet()) {
            final String testName = entry.getKey();
            final TestBucket testBucket = entry.getValue();
            final ConsumableTestDefinition testDefinition = testDefinitions.get(testName);
            if ((testDefinition != null && testDefinition.getSilent()) || testBucket.getValue() < 0) {
                continue;
            }
            builder.add(testName);
        }
        return builder.build();
    }

    /**
     * Appends test groups in the form without allocation ids as [test name + bucket value] for given test names.
     */
    protected final void appendTestGroupsWithoutAllocations(final StringBuilder sb, final char separator, final List<String> testNames) {
        for (final String testName : testNames) {
            final TestBucket testBucket = proctorResult.getBuckets().get(testName);
            if (testBucket != null) {
                sb.append(testName).append(testBucket.getValue()).append(separator);
            }
        }
    }

    /**
     * Appends test groups in the form with allocation ids as [allocation id + ":" + test name + bucket value] for given test names.
     */
    protected final void appendTestGroupsWithAllocations(final StringBuilder sb, final char separator, final List<String> testNames) {
        for (final String testName : testNames) {
            final TestBucket testBucket = proctorResult.getBuckets().get(testName);
            final Allocation allocation = proctorResult.getAllocations().get(testName);
            if ((testBucket != null) && (allocation != null) && !Strings.isNullOrEmpty(allocation.getId())) {
                sb.append(allocation.getId())
                        .append(ALLOCATION_GROUP_SEPARATOR)
                        .append(testName).append(testBucket.getValue()).append(separator);
            }
        }
    }

    /**
     * Generates a Map that be serialized to JSON and used with
     * indeed.proctor.groups.init and
     * indeed.proctor.groups.inGroup(tstName, bucketValue)
     *
     * @return a {@link Map} of config JSON
     */
    public Map<String, Integer> getJavaScriptConfig() {
        // For now this is a simple mapping from {testName to bucketValue}
        final Map<String, Integer> groups = Maps.newHashMapWithExpectedSize(proctorResult.getBuckets().size());
        for (final Entry<String, TestBucket> entry : proctorResult.getBuckets().entrySet()) {
            final String testName = entry.getKey();
            final TestBucket testBucket = entry.getValue();
            // mirrors appendTestGroups method by skipping *inactive* tests
            if (testBucket.getValue() < 0) {
                continue;
            }
            groups.put(testName, testBucket.getValue());
        }
        return groups;
    }

    /**
     * Generates a list of [bucketValue, payloadValue]'s for each test defined in the client app's proctor spec.
     *
     * To be used with generated javascript files from 'ant generate-proctor-js' by serializing the list
     * to a string and passing it to {packageName}.init();
     *
     * @param tests an alphabetical list of Test enums from your generated proctor java subclass of {@link com.indeed.proctor.consumer.AbstractGroups}.
     * @param <E> Generic Type of Test
     * @return a list of 2-element lists that hold the bucketValue and payloadValue for each test in alphabetical order
     */
    public <E extends Test> List<List<Object>> getJavaScriptConfig(final E[] tests) {
        final Map<String, TestBucket> buckets = getProctorResult().getBuckets();
        final List<List<Object>> groups = new ArrayList<List<Object>>(tests.length);
        for (final E test : tests) {
            final String testName = test.getName();
            final Integer bucketValue = getValue(testName, test.getFallbackValue());
            final Object payloadValue;
            final TestBucket testBucket = buckets.get(testName);
            if (testBucket != null && testBucket.getPayload() != null) {
                final Payload payload = testBucket.getPayload();
                payloadValue = payload.fetchAValue();
            } else {
                payloadValue = null;
            }
            final List<Object> definition = new ArrayList<Object>();
            definition.add(bucketValue);
            definition.add(payloadValue);
            groups.add(definition);
        }
        return groups;
    }

    public ProctorResult getProctorResult() {
        return proctorResult;
    }
}

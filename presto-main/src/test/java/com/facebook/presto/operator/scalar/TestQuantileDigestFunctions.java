/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.operator.scalar;

import com.facebook.airlift.stats.QuantileDigest;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.type.SqlVarbinary;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.spi.type.TypeParameter;
import com.facebook.presto.type.TypeRegistry;
import com.google.common.collect.ImmutableList;
import org.testng.annotations.Test;

import java.util.List;

import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.QuantileDigestParametricType.QDIGEST;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static org.testng.Assert.assertEquals;

public class TestQuantileDigestFunctions
        extends AbstractTestFunctions
{
    private static final TypeRegistry TYPE_REGISTRY = new TypeRegistry();
    private static final Type QDIGEST_BIGINT = QDIGEST.createType(TYPE_REGISTRY, ImmutableList.of(TypeParameter.of(BIGINT)));

    @Test
    public void testNullQuantileDigestGetValueAtQuantile()
    {
        functionAssertions.assertFunction("value_at_quantile(CAST(NULL AS qdigest(bigint)), 0.3)", BIGINT, null);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testGetValueAtQuantileOverOne()
    {
        QuantileDigest qdigest = new QuantileDigest(1);
        functionAssertions.assertFunction(format("value_at_quantile(CAST(X'%s' AS qdigest(bigint)), 1.5)", toHexString(qdigest)),
                BIGINT,
                null);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testGetValueAtQuantileBelowZero()
    {
        QuantileDigest qdigest = new QuantileDigest(1);
        functionAssertions.assertFunction(format("value_at_quantile(CAST(X'%s' AS qdigest(bigint)), -0.2)", toHexString(qdigest)),
                BIGINT,
                null);
    }

    @Test
    public void testValueAtQuantileBigint()
    {
        QuantileDigest qdigest = new QuantileDigest(1);
        addAll(qdigest, asList(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L));

        functionAssertions.assertFunction(format("value_at_quantile(CAST(X'%s' AS qdigest(bigint)), 0.5)", toHexString(qdigest)),
                BIGINT,
                5L);
    }

    @Test(expectedExceptions = PrestoException.class)
    public void testScaleNagative()
    {
        QuantileDigest qdigest = new QuantileDigest(1);
        addAll(qdigest, asList(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L));

        functionAssertions.selectSingleValue(format("scale_qdigest(CAST(X'%s' AS qdigest(bigint)), -1)", toHexString(qdigest)), QDIGEST_BIGINT, SqlVarbinary.class);
    }

    @Test
    public void testScale()
    {
        QuantileDigest qdigest = new QuantileDigest(1);
        addAll(qdigest, asList(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L));

        // Before scaling.
        assertEquals(qdigest.getHistogram(asList(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L)),
                asList(new QuantileDigest.Bucket(0, Double.NaN),
                        new QuantileDigest.Bucket(1, 0),
                        new QuantileDigest.Bucket(1, 1),
                        new QuantileDigest.Bucket(1, 2),
                        new QuantileDigest.Bucket(1, 3),
                        new QuantileDigest.Bucket(1, 4),
                        new QuantileDigest.Bucket(1, 5),
                        new QuantileDigest.Bucket(1, 6),
                        new QuantileDigest.Bucket(1, 7),
                        new QuantileDigest.Bucket(1, 8),
                        new QuantileDigest.Bucket(1, 9)));

        // Scale up.
        SqlVarbinary sqlVarbinary = functionAssertions.selectSingleValue(format("scale_qdigest(CAST(X'%s' AS qdigest(bigint)), 2)", toHexString(qdigest)),
                QDIGEST_BIGINT, SqlVarbinary.class);

        QuantileDigest scaledQdigest = new QuantileDigest(wrappedBuffer(sqlVarbinary.getBytes()));

        assertEquals(scaledQdigest.getHistogram(asList(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L)),
                asList(new QuantileDigest.Bucket(0, Double.NaN),
                        new QuantileDigest.Bucket(2, 0),
                        new QuantileDigest.Bucket(2, 1),
                        new QuantileDigest.Bucket(2, 2),
                        new QuantileDigest.Bucket(2, 3),
                        new QuantileDigest.Bucket(2, 4),
                        new QuantileDigest.Bucket(2, 5),
                        new QuantileDigest.Bucket(2, 6),
                        new QuantileDigest.Bucket(2, 7),
                        new QuantileDigest.Bucket(2, 8),
                        new QuantileDigest.Bucket(2, 9)));

        // Scale down.
        sqlVarbinary = functionAssertions.selectSingleValue(format("scale_qdigest(CAST(X'%s' AS qdigest(bigint)), 0.5)", toHexString(qdigest)), QDIGEST_BIGINT, SqlVarbinary.class);

        scaledQdigest = new QuantileDigest(wrappedBuffer(sqlVarbinary.getBytes()));

        assertEquals(scaledQdigest.getHistogram(asList(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L)),
                asList(new QuantileDigest.Bucket(0, Double.NaN),
                        new QuantileDigest.Bucket(0.5, 0),
                        new QuantileDigest.Bucket(0.5, 1),
                        new QuantileDigest.Bucket(0.5, 2),
                        new QuantileDigest.Bucket(0.5, 3),
                        new QuantileDigest.Bucket(0.5, 4),
                        new QuantileDigest.Bucket(0.5, 5),
                        new QuantileDigest.Bucket(0.5, 6),
                        new QuantileDigest.Bucket(0.5, 7),
                        new QuantileDigest.Bucket(0.5, 8),
                        new QuantileDigest.Bucket(0.5, 9)));
    }

    private void addAll(QuantileDigest digest, List<Long> values)
    {
        for (Long value : values) {
            digest.add(value);
        }
    }

    private String toHexString(QuantileDigest qdigest)
    {
        return new SqlVarbinary(qdigest.serialize().getBytes()).toString().replaceAll("\\s+", " ");
    }
}

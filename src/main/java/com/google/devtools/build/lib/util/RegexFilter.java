// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.util;

import com.google.common.base.Joiner;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec;
import com.google.devtools.build.lib.skyframe.serialization.SerializationException;
import com.google.devtools.common.options.Converter;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.annotation.Nullable;

/**
 * Handles options that specify list of included/excluded regex expressions. Validates whether
 * string is included in that filter.
 *
 * <p>String is considered to be included into the filter if it does not match any of the excluded
 * regex expressions and if it matches at least one included regex expression.
 */
@Immutable
public final class RegexFilter {
  // Null inclusion or exclusion pattern means those patterns are not used.
  @Nullable private final Pattern inclusionPattern;
  @Nullable private final Pattern exclusionPattern;
  private final int hashCode;

  /**
   * Converts from a colon-separated list of regex expressions with optional -/+ prefix into the
   * RegexFilter. Colons prefixed with backslash are considered to be part of regex definition and
   * not a delimiter between separate regex expressions.
   *
   * <p>Order of expressions is not important. Empty entries are ignored. '-' marks an excluded
   * expression.
   */
  public static class RegexFilterConverter implements Converter<RegexFilter> {

    @Override
    public RegexFilter convert(String input) throws OptionsParsingException {
      List<String> inclusionList = new ArrayList<>();
      List<String> exclusionList = new ArrayList<>();

      for (String piece : input.split("(?<!\\\\),")) { // Split on ',' but not on '\,'
        piece = piece.replace("\\,", ",");
        boolean isExcluded = piece.startsWith("-");
        if (isExcluded || piece.startsWith("+")) {
          piece = piece.substring(1);
        }
        if (piece.length() > 0) {
          (isExcluded ? exclusionList : inclusionList).add(piece);
        }
      }

      try {
        return new RegexFilter(inclusionList, exclusionList);
      } catch (PatternSyntaxException e) {
        throw new OptionsParsingException("Failed to build valid regular expression: "
            + e.getMessage());
      }
    }

    @Override
    public String getTypeDescription() {
      return "a comma-separated list of regex expressions with prefix '-' specifying"
          + " excluded paths";
    }
  }

  /**
   * Constructor taking regexes directly.
   *
   * <p>Null {@code inclusionRegex} or {@code exclusionRegex} means that inclusion or exclusion
   * matching will not be applied, respectively.
   *
   * <p>May throw {@link PatternSyntaxException}.
   */
  public RegexFilter(@Nullable String inclusionRegex, @Nullable String exclusionRegex) {
    this.inclusionPattern = inclusionRegex == null ? null : Pattern.compile(inclusionRegex);
    this.exclusionPattern = exclusionRegex == null ? null : Pattern.compile(exclusionRegex);
    this.hashCode = Objects.hash(inclusionRegex, exclusionRegex);
  }

  /** Creates new RegexFilter using provided inclusion and exclusion path lists. */
  public RegexFilter(List<String> inclusions, List<String> exclusions) {
    this(takeUnionOfRegexes(inclusions), takeUnionOfRegexes(exclusions));
  }

  /**
   * Converts a list of regex expressions into a single regex representing its union or null when
   * the list is empty.
   */
  private static String takeUnionOfRegexes(List<String> regexList) {
    if (regexList.isEmpty()) {
      return null;
    }
    // Wraps each individual regex into an independent group, then combines them using '|' and
    // wraps the result in a non-capturing group.
    return "(?:(?>" + Joiner.on(")|(?>").join(regexList) + "))";
  }

  /**
   * @return true iff given string is included (it does not match exclusion pattern (if any) and
   *     matches inclusionPatter (if any)).
   */
  public boolean isIncluded(String value) {
    if (exclusionPattern != null && exclusionPattern.matcher(value).find()) {
      return false;
    }
    if (inclusionPattern == null) {
      return true;
    }
    return inclusionPattern.matcher(value).find();
  }

  String getInclusionRegex() {
    return inclusionPattern == null ? null : inclusionPattern.pattern();
  }

  String getExclusionRegex() {
    return exclusionPattern == null ? null : exclusionPattern.pattern();
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    if (inclusionPattern != null) {
      builder.append(inclusionPattern.pattern().replace(",", "\\,"));
      if (exclusionPattern != null) {
        builder.append(",");
      }
    }
    if (exclusionPattern != null) {
      builder.append("-");
      builder.append(exclusionPattern.pattern().replace(",", "\\,"));
    }
    return builder.toString();
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof RegexFilter)) {
      return false;
    }

    RegexFilter otherFilter = (RegexFilter) other;
    if (this.exclusionPattern == null ^ otherFilter.exclusionPattern == null) {
      return false;
    }
    if (this.inclusionPattern == null ^ otherFilter.inclusionPattern == null) {
      return false;
    }
    if (this.exclusionPattern != null && !this.exclusionPattern.pattern().equals(
        otherFilter.exclusionPattern.pattern())) {
      return false;
    }
    if (this.inclusionPattern != null && !this.inclusionPattern.pattern().equals(
        otherFilter.inclusionPattern.pattern())) {
      return false;
    }
    return true;
  }

  @Override
  public int hashCode() {
    return hashCode;
  }

  public static final ObjectCodec<RegexFilter> CODEC = new Codec();

  // TODO(shahan): replace with @AutoCodec once it's ready.
  private static class Codec implements ObjectCodec<RegexFilter> {

    @Override
    public Class<RegexFilter> getEncodedClass() {
      return RegexFilter.class;
    }

    @Override
    public void serialize(RegexFilter filter, CodedOutputStream codedOut)
        throws SerializationException, IOException {
      serializeFilterString(filter.getInclusionRegex(), codedOut);
      serializeFilterString(filter.getExclusionRegex(), codedOut);
    }

    private void serializeFilterString(@Nullable String regex, CodedOutputStream codedOut)
        throws IOException {
      if (regex == null) {
        codedOut.writeBoolNoTag(false);
      } else {
        codedOut.writeBoolNoTag(true);
        codedOut.writeStringNoTag(regex);
      }
    }

    @Override
    public RegexFilter deserialize(CodedInputStream codedIn)
        throws SerializationException, IOException {
      return new RegexFilter(deserializeFilterString(codedIn), deserializeFilterString(codedIn));
    }

    private String deserializeFilterString(CodedInputStream codedIn) throws IOException {
      return codedIn.readBool() ? codedIn.readString() : null;
    }
  }
}

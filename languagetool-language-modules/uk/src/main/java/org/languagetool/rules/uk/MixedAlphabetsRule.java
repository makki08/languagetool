/* LanguageTool, a natural language style checker 
 * Copyright (C) 2005 Daniel Naber (http://www.danielnaber.de)
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301
 * USA
 */
package org.languagetool.rules.uk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;
import org.languagetool.AnalyzedSentence;
import org.languagetool.AnalyzedTokenReadings;
import org.languagetool.rules.Category;
import org.languagetool.rules.Rule;
import org.languagetool.rules.RuleMatch;

/**
 * A rule that matches words latin and cyrillic characters in them
 * 
 * @author Andriy Rysin
 */
public class MixedAlphabetsRule extends Rule {
	private static final Pattern MIXED_ALPHABETS = Pattern.compile(".*([a-zA-Z][а-яіїєґА-ЯІЇЄҐ]|[а-яіїєґІЇЄҐ]'?[a-zA-Z]).*");
	private static final Pattern CYRILLIC_ONLY = Pattern.compile(".*[бвгґдєжзйїлнпфцчшщьюяБГҐДЄЖЗИЙЇЛПФЦЧШЩЬЮЯ].*");
	private static final Pattern LATIN_ONLY = Pattern.compile(".*[bdfghjlqrsvzDFGLNQRSUVZ].*");

	public MixedAlphabetsRule(final ResourceBundle messages) throws IOException {
		if (messages != null) {
			super.setCategory(new Category(messages.getString("category_misc")));
		}
	}

	@Override
	public final String getId() {
		return "UK_MIXED_ALPHABETS";
	}

	@Override
	public String getDescription() {
		return "Змішування кирилиці й латиниці";
	}

	public String getShort() {
		return "Мішанина розкладок";
	}

	public String getSuggestion(String word) {
		String highlighted = word.replaceAll("([a-zA-Z])([а-яіїєґА-ЯІЇЄҐ])", "$1/$2");
		highlighted = highlighted.replaceAll("([а-яіїєґА-ЯІЇЄҐ])([a-zA-Z])", "$1/$2");
		return " містить суміш кирилиці та латиниці: «"+ highlighted +"», виправлення: ";
	}

	/**
	 * Indicates if the rule is case-sensitive. 
	 * @return true if the rule is case-sensitive, false otherwise.
	 */
	public boolean isCaseSensitive() {
		return true;  
	}

	@Override
	public final RuleMatch[] match(final AnalyzedSentence text) {
		List<RuleMatch> ruleMatches = new ArrayList<RuleMatch>();
		AnalyzedTokenReadings[] tokens = text.getTokensWithoutWhitespace();

		for (AnalyzedTokenReadings tokenReadings: tokens) {
			String tokenString = tokenReadings.getToken();

			if( MIXED_ALPHABETS.matcher(tokenString).matches() ) {

				List<String> replacements = new ArrayList<String>();

				if( ! LATIN_ONLY.matcher(tokenString).matches() ) {
					replacements.add( toCyrillic(tokenString) );
				}
				if( ! CYRILLIC_ONLY.matcher(tokenString).matches() ) {
					replacements.add( toLatin(tokenString) );
				}

				if ( replacements.size() > 0 ) {
					RuleMatch potentialRuleMatch = createRuleMatch(tokenReadings, replacements);
					ruleMatches.add(potentialRuleMatch);
				}
			}
		}
		return toRuleMatchArray(ruleMatches);
	}

	private RuleMatch createRuleMatch(AnalyzedTokenReadings tokenReadings, List<String> replacements) {
		String tokenString = tokenReadings.getToken();
		String msg = tokenString + getSuggestion(tokenString) + StringUtils.join(replacements, ", ");
		int pos = tokenReadings.getStartPos();

		RuleMatch potentialRuleMatch = new RuleMatch(this, pos, pos + tokenString.length(), msg, getShort());

		potentialRuleMatch.setSuggestedReplacements(replacements);

		return potentialRuleMatch;
	}

	@Override
	public void reset() {
	}

	private static HashMap<Character, Character> toLatMap = new HashMap<Character, Character>();
	private static HashMap<Character, Character> toCyrMap = new HashMap<Character, Character>();
	private static String cyrChars = "аеікморстухАВЕІКМНОРСТУХ";
	private static String latChars = "aeikmopctyxABEIKMHOPCTYX";

	static {
		for(int i=0; i<cyrChars.length(); i++) {
			toLatMap.put(cyrChars.charAt(i), latChars.charAt(i));
			toCyrMap.put(latChars.charAt(i), cyrChars.charAt(i));
		}
	}

	private static String toCyrillic(String word) {
		for(Map.Entry<Character, Character> entry: toCyrMap.entrySet()) {
			word = word.replace(entry.getKey(), entry.getValue());
		}
		return word;
	}

	private static String toLatin(String word) {
		for(Map.Entry<Character, Character> entry: toLatMap.entrySet()) {
			word = word.replace(entry.getKey(), entry.getValue());
		}
		return word;
	}

}
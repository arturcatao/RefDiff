package refdiff.core.diff.similarity;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.*;

import java.util.Arrays;

import org.junit.Test;

import refdiff.core.cst.CstNode;

public class TestTfIdfSourceRepresentationBuilder {
	
	@Test
	public void shouldIgnoreSyntheticTokensMissingFromVocabulary() {
		TfIdfSourceRepresentationBuilder builder = new TfIdfSourceRepresentationBuilder();
		CstNode node = new CstNode(1);
		node.setSimpleName("call");
		node.setLocalName("call/2");
		
		TfIdfSourceRepresentation source = builder.buildForNode(node, true,
			Arrays.asList("(", ")", "a", "b"));
		TfIdfSourceRepresentation fragment = builder.buildForFragment(
			Arrays.asList("call", "(", ")", ",", "a", "b"));
		
		assertThat(fragment.getSize(), is(5));
		assertThat(builder.similarity(source, fragment), is(1.0));
	}
	
	@Test
	public void shouldBuildEmptyFragmentWhenAllTokensAreMissingFromVocabulary() {
		TfIdfSourceRepresentationBuilder builder = new TfIdfSourceRepresentationBuilder();
		
		TfIdfSourceRepresentation fragment = builder.buildForFragment(Arrays.asList(","));
		
		assertThat(fragment.getSize(), is(0));
	}
	
}

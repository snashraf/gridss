package au.edu.wehi.idsv;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.metrics.Header;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.google.common.collect.Lists;


public class SAMRecordAssemblyEvidenceTest extends TestHelper {
	@Test
	public void should_create_SAMRecord_for_assembly() {
		SAMRecordAssemblyEvidence e = AssemblyFactory.createAnchoredBreakend(getContext(), AES(), FWD, null,
			1, 2, 1, B("GTAC"), new byte[] {1,2,3,4}, new int[] {1, 2});
		assertNotNull(e.getSAMRecord());
		assertEquals("GTAC", S(e.getSAMRecord().getReadBases()));
		assertArrayEquals( new byte[] {1,2,3,4}, e.getSAMRecord().getBaseQualities());
	}
	@Test
	public void should_create_placeholder_paired_read() {
		SAMRecordAssemblyEvidence e = AssemblyFactory.createAnchoredBreakend(getContext(), AES(), FWD, null,
			1, 2, 1, B("GTAC"), new byte[] {1,2,3,4}, new int[] {1, 2});
		assertPairing(e.getSAMRecord(), e.getRemoteSAMRecord());
		assertTrue(e.getRemoteSAMRecord().getReadUnmappedFlag());
	}
	@Test
	public void anchor_positions_should_match_genomic() {
		SAMRecordAssemblyEvidence fwd = AssemblyFactory.createAnchoredBreakend(getContext(), AES(), FWD, null, 1, 10, 3, B("GTACCCA"), new byte[] { 1, 2, 3, 4, 4, 8, 8 }, new int[] {2, 0});
		SAMRecordAssemblyEvidence bwd = AssemblyFactory.createAnchoredBreakend(getContext(), AES(), BWD, null, 1, 10, 3, B("GTACCCA"), new byte[] { 1, 2, 3, 4, 4, 8, 8 }, new int[] {2, 0});
		assertEquals(1, (int)fwd.getSAMRecord().getReferenceIndex());
		assertEquals(1, (int)bwd.getSAMRecord().getReferenceIndex());
		assertEquals(8, fwd.getSAMRecord().getAlignmentStart());
		assertEquals(10, bwd.getSAMRecord().getAlignmentStart());
		assertEquals("3M4S", fwd.getSAMRecord().getCigarString());
		assertEquals("4S3M", bwd.getSAMRecord().getCigarString());
	}
	@Test
	public void unanchor_positions_should_match_genomic() {
		SAMRecordAssemblyEvidence fwd = AssemblyFactory.createUnanchoredBreakend(getContext(), AES(), new BreakendSummary(1, FWD, 5, 10), null, B("AAA"), B("AAA"), new int[] {2, 0});
		SAMRecordAssemblyEvidence bwd = AssemblyFactory.createUnanchoredBreakend(getContext(), AES(), new BreakendSummary(1, BWD, 5, 10), null, B("AAA"), B("AAA"), new int[] {2, 0});
		assertEquals(1, (int)fwd.getSAMRecord().getReferenceIndex());
		assertEquals(1, (int)bwd.getSAMRecord().getReferenceIndex());
		assertEquals(5, fwd.getSAMRecord().getAlignmentStart());
		assertEquals(5, bwd.getSAMRecord().getAlignmentStart());
		assertEquals(10, fwd.getSAMRecord().getAlignmentEnd());
		assertEquals(10, bwd.getSAMRecord().getAlignmentEnd());
		assertEquals("1X4N1X3S", fwd.getSAMRecord().getCigarString());
		assertEquals("3S1X4N1X", bwd.getSAMRecord().getCigarString());
	}
	private void assertPairing(SAMRecord assembly, SAMRecord realign) {
		assertNotNull(assembly);
		assertNotNull(realign);
		assertTrue(assembly.getReadPairedFlag());
		assertTrue(realign.getReadPairedFlag());
		assertTrue(assembly.getFirstOfPairFlag());
		assertFalse(realign.getFirstOfPairFlag());
		assertFalse(assembly.getSecondOfPairFlag());
		assertTrue(realign.getSecondOfPairFlag());
		assertEquals(assembly.getReadName(), realign.getReadName());
		assertMateFields(assembly, realign);
		assertMateFields(realign, assembly);
	}
	private void assertMateFields(SAMRecord r, SAMRecord mate) {
		assertEquals(r.getMateNegativeStrandFlag(), mate.getReadNegativeStrandFlag());
		assertEquals(r.getMateUnmappedFlag(), mate.getReadUnmappedFlag());
		assertEquals(r.getMateReferenceIndex(), mate.getReferenceIndex());
		assertEquals(r.getMateAlignmentStart(), mate.getAlignmentStart());
	}
	@Test
	public void should_use_MS_for_anchored_breakend() {
		assertEquals("1M3S", AssemblyFactory.createAnchoredBreakend(getContext(), AES(), FWD, null,
				1, 2, 1, B("GTAC"), new byte[] {1,2,3,4}, new int[] {1, 2}).getSAMRecord().getCigarString());
		assertEquals("3S1M", AssemblyFactory.createAnchoredBreakend(getContext(), AES(), BWD, null,
				1, 2, 1, B("GTAC"), new byte[] {1,2,3,4}, new int[] {1, 2}).getSAMRecord().getCigarString());
	}
	@Test
	public void should_use_XNXS_for_unanchored_interval_breakend() {
		assertEquals("1X1N1X4S", AssemblyFactory.createUnanchoredBreakend(getContext(), AES(), new BreakendSummary(0, FWD, 1, 3), null, B("GTAC"), new byte[] {1,2,3,4}, new int[] {0, 0}).getSAMRecord().getCigarString());
		assertEquals("4S1X18N1X", AssemblyFactory.createUnanchoredBreakend(getContext(), AES(), new BreakendSummary(0, BWD, 10, 20), null, B("GTAC"), new byte[] {1,2,3,4}, new int[] {0, 0}).getSAMRecord().getCigarString());
	}
	@Test
	public void should_use_minimal_cigar_representation() {
		assertEquals("1X3S", AssemblyFactory.createUnanchoredBreakend(getContext(), AES(), new BreakendSummary(1, FWD, 5, 5), null, B("AAA"), B("AAA"), new int[] {2, 0}).getSAMRecord().getCigarString());
		assertEquals("2X3S", AssemblyFactory.createUnanchoredBreakend(getContext(), AES(), new BreakendSummary(1, FWD, 5, 6), null, B("AAA"), B("AAA"), new int[] {2, 0}).getSAMRecord().getCigarString());
		assertEquals("1X1N1X3S", AssemblyFactory.createUnanchoredBreakend(getContext(), AES(), new BreakendSummary(1, FWD, 5, 7), null, B("AAA"), B("AAA"), new int[] {2, 0}).getSAMRecord().getCigarString());
	}
	@Test
	public void SAMRecord_round_trip_should_be_unchanged() {
		for (SAMRecordAssemblyEvidence e : new SAMRecordAssemblyEvidence[] {
				AssemblyFactory.createUnanchoredBreakend(getContext(), AES(), new BreakendSummary(0, FWD, 1000, 1299), null, B("GTAC"), new byte[] {1,2,3,4}, new int[] {0, 0}).annotateAssembly(),
				AssemblyFactory.createUnanchoredBreakend(getContext(), AES(), new BreakendSummary(0, FWD, 701, 1000), null, B("GTAC"), new byte[] {1,2,3,4}, new int[] {0, 0}).annotateAssembly(),
				AssemblyFactory.createAnchoredBreakend(getContext(), AES(), FWD, null, 1, 2, 1, B("GTAC"), new byte[] {1,2,3,4}, new int[] {1, 2}).annotateAssembly(),
				AssemblyFactory.createAnchoredBreakend(getContext(), AES(), BWD, null, 1, 2, 1, B("GTAC"), new byte[] {1,2,3,4}, new int[] {1, 2}).annotateAssembly(),
				big(),
			}) {
			SAMRecordAssemblyEvidence r = new SAMRecordAssemblyEvidence(e.getEvidenceSource(), e.getSAMRecord(), null);
			assertEvidenceEquals(e, r);
		}
	}
	public static void assertEvidenceEquals(AssemblyEvidence e, AssemblyEvidence r) {
		assertEquals(e.getAssemblyAnchorLength(), r.getAssemblyAnchorLength());
		//assertArrayEquals(e.getAssemblyAnchorQuals() , r.getAssemblyAnchorQuals());
		assertEquals(S(e.getAssemblyAnchorSequence()) , S(r.getAssemblyAnchorSequence()));
		assertEquals(e.getAssemblyBaseCount(EvidenceSubset.NORMAL) , r.getAssemblyBaseCount(EvidenceSubset.NORMAL));
		assertEquals(e.getAssemblyBaseCount(EvidenceSubset.TUMOUR) , r.getAssemblyBaseCount(EvidenceSubset.TUMOUR));
		assertEquals(e.getAssemblyBaseCount(EvidenceSubset.ALL) , r.getAssemblyBaseCount(EvidenceSubset.ALL));
		assertEquals(e.getAssemblyReadPairLengthMax(EvidenceSubset.NORMAL) , r.getAssemblyReadPairLengthMax(EvidenceSubset.NORMAL));
		assertEquals(e.getAssemblyReadPairLengthMax(EvidenceSubset.TUMOUR) , r.getAssemblyReadPairLengthMax(EvidenceSubset.TUMOUR));
		assertEquals(e.getAssemblyReadPairLengthMax(EvidenceSubset.ALL) , r.getAssemblyReadPairLengthMax(EvidenceSubset.ALL));
		assertEquals(S(e.getAssemblySequence()) , S(r.getAssemblySequence()));
		assertEquals(e.getAssemblySoftClipLengthMax(EvidenceSubset.NORMAL) , r.getAssemblySoftClipLengthMax(EvidenceSubset.NORMAL));
		assertEquals(e.getAssemblySoftClipLengthMax(EvidenceSubset.TUMOUR) , r.getAssemblySoftClipLengthMax(EvidenceSubset.TUMOUR));
		assertEquals(e.getAssemblySoftClipLengthMax(EvidenceSubset.ALL) , r.getAssemblySoftClipLengthMax(EvidenceSubset.ALL));
		assertEquals(e.getAssemblySoftClipLengthTotal(EvidenceSubset.NORMAL) , r.getAssemblySoftClipLengthTotal(EvidenceSubset.NORMAL));
		assertEquals(e.getAssemblySoftClipLengthTotal(EvidenceSubset.TUMOUR) , r.getAssemblySoftClipLengthTotal(EvidenceSubset.TUMOUR));
		assertEquals(e.getAssemblySoftClipLengthTotal(EvidenceSubset.ALL) , r.getAssemblySoftClipLengthTotal(EvidenceSubset.ALL));
		assertEquals(e.getAssemblySupportCountReadPair(EvidenceSubset.NORMAL) , r.getAssemblySupportCountReadPair(EvidenceSubset.NORMAL));
		assertEquals(e.getAssemblySupportCountReadPair(EvidenceSubset.TUMOUR) , r.getAssemblySupportCountReadPair(EvidenceSubset.TUMOUR));
		assertEquals(e.getAssemblySupportCountReadPair(EvidenceSubset.ALL) , r.getAssemblySupportCountReadPair(EvidenceSubset.ALL));
		assertEquals(e.getAssemblySupportCountSoftClip(EvidenceSubset.NORMAL) , r.getAssemblySupportCountSoftClip(EvidenceSubset.NORMAL));
		assertEquals(e.getAssemblySupportCountSoftClip(EvidenceSubset.TUMOUR) , r.getAssemblySupportCountSoftClip(EvidenceSubset.TUMOUR));
		assertEquals(e.getAssemblySupportCountSoftClip(EvidenceSubset.ALL) , r.getAssemblySupportCountSoftClip(EvidenceSubset.ALL));
		assertArrayEquals(e.getBreakendQuality() , r.getBreakendQuality());
		assertEquals(S(e.getBreakendSequence()) , S(r.getBreakendSequence()));
		assertEquals(e.getBreakendSummary(), r.getBreakendSummary());
		assertEquals(e.getBreakendSummary().getClass(), r.getBreakendSummary().getClass());
		assertEquals(e.getEvidenceID() , r.getEvidenceID());
		assertEquals(e.getEvidenceSource() , r.getEvidenceSource());
		assertEquals(e.getFilters() , r.getFilters());
		assertEquals(e.getLocalBaseLength() , r.getLocalBaseLength());
		assertEquals(e.getLocalMapq() , r.getLocalMapq());
		assertEquals(e.getLocalMaxBaseQual() , r.getLocalMaxBaseQual());
		assertEquals(e.getLocalTotalBaseQual() , r.getLocalTotalBaseQual());
		if (e instanceof DirectedBreakpoint) {
			assertTrue(r instanceof DirectedBreakpoint);
			DirectedBreakpoint de = (DirectedBreakpoint)e;
			DirectedBreakpoint dr = (DirectedBreakpoint)r;
			assertEquals(de.getBreakendSummary(), dr.getBreakendSummary());
			assertEquals(de.getRemoteMapq(), dr.getRemoteMapq());
			assertEquals(de.getRemoteBaseLength(), dr.getRemoteBaseLength());
			assertEquals(de.getRemoteBaseCount(), dr.getRemoteBaseCount());
			assertEquals(de.getRemoteMaxBaseQual(), dr.getRemoteMaxBaseQual());
			assertEquals(de.getRemoteTotalBaseQual(), dr.getRemoteTotalBaseQual());
			assertEquals(de.getUntemplatedSequence(), dr.getUntemplatedSequence());
		}
	}
	private SAMRecordAssemblyEvidence big() {
		return new AssemblyFactoryTest().big();
	}
	@Test
	public void should_track_breakend_evidence() {
		DirectedEvidence e1 = SCE(BWD, Read(0, 1, "5S5M"));
		DirectedEvidence e2 = SCE(BWD, Read(0, 1, "6S5M"));
		DirectedEvidence e3 = NRRP(OEA(0, 1, "1M", false));
		DirectedEvidence b1 = SCE(BWD, Read(0, 1, "7S5M"));
		SAMRecordAssemblyEvidence e = AssemblyFactory.createAnchoredBreakend(getContext(), AES(), FWD, Lists.transform(Lists.newArrayList(e1, e2, e3), EID),
			1, 2, 1, B("GTAC"), new byte[] {1,2,3,4}, new int[] {1, 2});
		assertTrue(e.isPartOfAssembly(e1));
		assertTrue(e.isPartOfAssembly(e2));
		assertTrue(e.isPartOfAssembly(e3));
		assertFalse(e.isPartOfAssembly(b1));
		e = new SAMRecordAssemblyEvidence(AES(), e.getSAMRecord(), null);
		assertTrue(e.isPartOfAssembly(e1));
		assertTrue(e.isPartOfAssembly(e2));
		assertTrue(e.isPartOfAssembly(e3));
		assertFalse(e.isPartOfAssembly(b1));
	}
	@Test
	public void should_rehydrate_breakend_evidence() {
		DirectedEvidence e1 = SCE(BWD, Read(0, 1, "5S5M"));
		DirectedEvidence e2 = SCE(BWD, Read(0, 1, "6S5M"));
		DirectedEvidence e3 = NRRP(OEA(0, 1, "1M", false));
		SAMRecordAssemblyEvidence e = AssemblyFactory.createAnchoredBreakend(getContext(), AES(), FWD, Lists.transform(Lists.newArrayList(e1, e2, e3), EID),
			1, 2, 1, B("GTAC"), new byte[] {1,2,3,4}, new int[] {1, 2});
		// evidence has not yet been hydrated
		assertEquals(0, e.getEvidence().size());
		e = new SAMRecordAssemblyEvidence(AES(), e.getSAMRecord(), null);
		e.hydrateEvidenceSet(e1);
		e.hydrateEvidenceSet(e2);
		e.hydrateEvidenceSet(e3);
		assertEquals(3, e.getEvidence().size());
	}
	@Test
	public void realign_should_shift_breakend_to_match_reference() {
		SAMRecordAssemblyEvidence e = AssemblyFactory.createAnchoredBreakend(getContext(), AES(), FWD, null,
				0, 5, 5, B("AAAATTTT"), new byte[] {1,2,3,4,1,2,3,4}, new int[] {0, 0}).realign();
		assertEquals("TTTT", S(e.getBreakendSequence()));
		assertEquals(new BreakendSummary(0, FWD, 4, 4), e.getBreakendSummary());
		assertEquals("4M4S", e.getSAMRecord().getCigarString());
	}
	@Test
	public void realign_should_align_to_reference_with_50bp_margin_around_expected_anchor_interval() {
		int margin = 50;
		for (int startpos = 300 - margin; startpos <= 300 + margin; startpos++) {
			String seq = S("N", 50) + S(Arrays.copyOfRange(RANDOM, 299, 399)); // genomic positions 300-400
			SAMRecordAssemblyEvidence e = AssemblyFactory.createAnchoredBreakend(getContext(), AES(), BWD, null,
					2, startpos, 100, B(seq), B(40, seq.length()), new int[] {0, 0}).realign();
			assertEquals(300, e.getBreakendSummary().start);
			assertEquals(50, e.getBreakendSequence().length);
		}
		// FWD breakend
		for (int startpos = 300 - margin; startpos <= 300 + margin; startpos++) {
			String seq = S(Arrays.copyOfRange(RANDOM, 299, 399)) + S("N", 50);
			SAMRecordAssemblyEvidence e = AssemblyFactory.createAnchoredBreakend(getContext(), AES(), FWD, null,
					2, startpos + 100, 100, B(seq), B(40, seq.length()), new int[] {0, 0}).realign();
			assertEquals(399, e.getBreakendSummary().start);
			assertEquals(50, e.getBreakendSequence().length);
		}
	}
	@Test
	public void realign_should_expand_window_by_breakend_length_to_allow_for_mapping_over_small_indels() {
		int indelSize = 20;
		String seq = "N" + S(Arrays.copyOfRange(RANDOM, 299-indelSize-100, 299-indelSize)) + S(Arrays.copyOfRange(RANDOM, 299, 399)); // genomic positions 300-400
		SAMRecordAssemblyEvidence e = AssemblyFactory.createAnchoredBreakend(getContext(), AES(), BWD, null,
				2, 300, 100, B(seq), B(40, seq.length()), new int[] {0, 0}).realign();
		assertEquals("1S100M20D100M", e.getSAMRecord().getCigarString());
		
		seq = S(Arrays.copyOfRange(RANDOM, 299, 399)) + S(Arrays.copyOfRange(RANDOM, 399+indelSize, 399+indelSize+100)) + "N";
		e = AssemblyFactory.createAnchoredBreakend(getContext(), AES(), FWD, null,
				2, 399, 100, B(seq), B(40, seq.length()), new int[] {0, 0}).realign();
		assertEquals("100M20D100M1S", e.getSAMRecord().getCigarString());
	}
	@Test
	public void realign_should_allow_small_anchor_deletion() {
		String seq = S(B('N', 100)) + S(Arrays.copyOfRange(RANDOM, 0, 100)) + S(Arrays.copyOfRange(RANDOM, 110, 210));
		SAMRecordAssemblyEvidence e = AssemblyFactory.createAnchoredBreakend(getContext(), AES(), BWD, null,
				2, 1, 210, B(seq), B(40, seq.length()), new int[] {0, 0}).realign();
		assertEquals("100S100M10D100M", e.getSAMRecord().getCigarString());
	}
	@Test
	public void realign_should_allow_small_anchor_insertion() {
		String seq = S(B('N', 100)) + S(Arrays.copyOfRange(RANDOM, 0, 100)) + "NNNNNNNNNN" + S(Arrays.copyOfRange(RANDOM, 100, 200));
		SAMRecordAssemblyEvidence e = AssemblyFactory.createAnchoredBreakend(getContext(), AES(), BWD, null,
				2, 1, 200, B(seq), B(40, seq.length()), new int[] {0, 0}).realign();
		assertEquals("100S100M10I100M", e.getSAMRecord().getCigarString());
	}
	@Test
	public void realign_should_abort_if_anchor_turns_into_soft_clip() {
		String seq = S(Arrays.copyOfRange(RANDOM, 0, 10)) + S(Arrays.copyOfRange(RANDOM, 30, 70));
		SAMRecordAssemblyEvidence e = AssemblyFactory.createAnchoredBreakend(getContext(), AES(), FWD, null,
				2, 1, 10, B(seq), B(40, seq.length()), new int[] {0, 0});
		assertEquals("10M40S", e.getSAMRecord().getCigarString());
		assertEquals("10M40S", e.realign().getSAMRecord().getCigarString());
	}
	@Rule
    public TemporaryFolder testFolder = new TemporaryFolder();
	@Test
	public void realign_should_fix_778_chr1_170849702_misalignment() {
		String assembly = "ATCCATCCCTATGACCCAAACATCTCCCACCAGGCCTCATGTTCAATATTAAAGATCACATTTCAACTTGAGATTTGGAGGGGACAAACATACAAATCATATCATTATCTCTCTCCCCACTTCTCTCTTTATCAATCCCTCCCTCTTTGTCAATCTTAGCCTTGGCCTTCAGATTTTACCACTTGATTTTTCACATTTTCTGTATTCTTAAT"
				+ "GATTATTATATTTTCATGTTCTTGCTAATCTATATCATGGTTAGAAATCAAAGCATGCCGAAATTTCTCTCTTACTTTTTTTGCTGTT";
		File ref = new File("src/test/resources/chr1_170849600_170849850.fa");
		ProcessingContext context = new ProcessingContext(getFSContext(),
				new ArrayList<Header>(), new SoftClipParameters(),
				new ReadPairParameters(), new AssemblyParameters(),
				new RealignmentParameters(), new VariantCallingParameters(),
				ref, false, false);
		SAMRecordAssemblyEvidence e = AssemblyFactory.createAnchoredBreakend(context, AES(context), BWD, null,
				0, 170849702-170849600+1, 97, B(assembly), B(40, assembly.length()), new int[] {0, 0}).realign();
		// anchor location is 11bp off
		assertEquals("212S88M", e.getSAMRecord().getCigarString());
		assertEquals(170849713-170849600+1, e.getBreakendSummary().start);
	}
	@Test
	public void small_indel_should_be_called_if_realignment_spans_event() {
		String assembly = "AAAAAAAAAATTAAAAAAAAAA";
		SAMRecordAssemblyEvidence e = AssemblyFactory.createAnchoredBreakend(getContext(), AES(), FWD, null,
				0, 1, 10, B(assembly), B(40, assembly.length()), new int[] {0, 0}).realign();
		assertEquals("10M2I10M", ((SmallIndelSAMRecordAssemblyEvidence)e).getBackingRecord().getCigarString());
		assertEquals(new BreakpointSummary(0, FWD, 10, 10, 0, BWD, 11, 11), e.getBreakendSummary());
		assertTrue(e instanceof DirectedBreakpoint);
	}
	/**
	 * Needed for realignment that turn out to match reference
	 */
	@Test
	public void should_allow_reference_allele_assemblies() {
		String assembly = "AAAAAAAAAAA";
		SAMRecordAssemblyEvidence e = AssemblyFactory.createAnchoredBreakend(getContext(), AES(), FWD, null,
				0, 1, assembly.length(), B(assembly), B(40, assembly.length()), new int[] {0, 0});
		assertEquals(assembly.length(), e.getAssemblyAnchorLength());
		assertEquals(0, e.getBreakendSequence().length);
		assertEquals(0, e.getBreakendQual(), 0);
	}
	@Test
	public void should_round_trip_reference_allele_assemblies() {
		String assembly = "AAAAAAAAAAA";
		SAMRecordAssemblyEvidence e = AssemblyFactory.createAnchoredBreakend(getContext(), AES(), FWD, null,
				0, 1, assembly.length(), B(assembly), B(40, assembly.length()), new int[] {0, 0});
		e = new SAMRecordAssemblyEvidence(AES(), e.getSAMRecord(), null);
		assertEquals(assembly.length(), e.getAssemblyAnchorLength());
		assertEquals(0, e.getBreakendSequence().length);
	}
	@Test
	public void should_allow_realignment_to_reference_allele() {
		String assembly = "AAAAAAAAAAA";
		SAMRecordAssemblyEvidence e = AssemblyFactory.createAnchoredBreakend(getContext(), AES(), FWD, null,
				0, 1, 1, B(assembly), B(40, assembly.length()), new int[] {0, 0});
		e = e.realign();
		assertEquals(assembly.length(), e.getAssemblyAnchorLength());
		assertEquals(0, e.getBreakendSequence().length);
	}
	@Test
	public void reference_allele_should_have_no_breakend_call() {
		// 12345678901234567890
		//          MMMMMMMMMM
		//         >>>>>>>>>>>
		//          <<<<<<<<<<<
		String assembly = S(Arrays.copyOfRange(RANDOM, 10-1, 20-1));
		SAMRecordAssemblyEvidence e = AssemblyFactory.createAnchoredBreakend(getContext(), AES(), FWD, null,
				2, 1, 1, B(assembly), B(40, assembly.length()), new int[] {0, 0});
		e = e.realign();
		assertNull(null, e.getBreakendSummary());
		
		e = AssemblyFactory.createAnchoredBreakend(getContext(), AES(), BWD, null,
				2, 1, 1, B(assembly), B(40, assembly.length()), new int[] {0, 0});
		e = e.realign();
		assertNull(null, e.getBreakendSummary());
	}
	@Test
	public void getAssemblySequence_should_return_assembly() {
		assertEquals("AAAA", S(AssemblyFactory.createAnchoredBreakend(getContext(), AES(), FWD, null,
				0, 1, 1, B("AAAA"), new byte[] {1,1,1,1}, new int[] {0, 0}).getAssemblySequence()));
		assertEquals("GTAC", S(AssemblyFactory.createUnanchoredBreakend(getContext(), AES(), new BreakendSummary(0, FWD, 1, 1), null, B("GTAC"), new byte[] {1,2,3,4}, new int[] {0, 0}).getAssemblySequence()));
		assertEquals("GTAC", S(AssemblyFactory.createUnanchoredBreakend(getContext(), AES(), new BreakendSummary(0, FWD, 1, 1), null, B("GTAC"), new byte[] {1,2,3,4}, new int[] {0, 0}).getAssemblySequence()));
	}
	@Test
	public void getBreakendQual_should_exclude_assembled_evidence_that_does_not_support_breakend() {
		List<DirectedEvidence> support = Lists.<DirectedEvidence>newArrayList(
				NRRP(OEA(0, 1, "1M", true)),
				NRRP(OEA(0, 2, "1M", true)),
				NRRP(OEA(0, 1000, "1M", true)));
		ProcessingContext pc = getContext();
		pc.getAssemblyParameters().excludeNonSupportingEvidence = false;
		SAMRecordAssemblyEvidence ass = AssemblyFactory.createUnanchoredBreakend(pc, AES(pc), new BreakendSummary(0, FWD, 1, 1), Lists.transform(support, EID), B("GTAC"), new byte[] {1,2,3,4}, new int[] {0, 0});
		ass.hydrateEvidenceSet(support);
		ass.annotateAssembly();
		assertEquals(support.get(0).getBreakendQual() + support.get(1).getBreakendQual() + support.get(2).getBreakendQual(), ass.getBreakendQual(), DELTA);
		
		pc.getAssemblyParameters().excludeNonSupportingEvidence = true;
		ass = AssemblyFactory.createUnanchoredBreakend(pc, AES(pc), new BreakendSummary(0, FWD, 1, 1), Lists.transform(support, EID), B("GTAC"), new byte[] {1,2,3,4}, new int[] {0, 0});
		ass.hydrateEvidenceSet(support);
		ass.annotateAssembly();
		assertEquals(support.get(0).getBreakendQual() + support.get(1).getBreakendQual(), ass.getBreakendQual(), DELTA);
	}
	@Test
	public void getAnchor_should_not_include_inexact_breakend_bases() {
		List<DirectedEvidence> support = Lists.<DirectedEvidence>newArrayList(NRRP(OEA(0, 1, "1M", true)));
		assertEquals(0, AssemblyFactory.createUnanchoredBreakend(getContext(), AES(), new BreakendSummary(0, FWD, 1, 1), null, B("GTAC"), new byte[] {1,2,3,4}, new int[] {0, 0}).getAssemblyAnchorSequence().length);
	}
	@Test
	public void breakpoint_should_use_indel_cigar() {
		List<DirectedEvidence> support = Lists.<DirectedEvidence>newArrayList();
		// 1234567890   1234567890
		//         MMIIIDDDDMMMM
		//         NNAAA    TTTT
		SAMRecord r = ((SAMRecordAssemblyEvidence)AssemblyFactory.createAnchoredBreakpoint(getContext(), AES(), null,
				0, 10, 2,
				0, 15, 4,
				B("NNAAATTTT"),
				B("ABCDEFGHI"), new int[] {0, 0})).getBackingRecord();
		assertEquals(0, (int)r.getReferenceIndex());
		assertEquals(9, r.getAlignmentStart());
		assertEquals("2M3I4D4M", r.getCigarString());
		assertEquals("ABCDEFGHI", S(r.getBaseQualities()));
		assertEquals("NNAAATTTT", S(r.getReadBases()));
	}
}
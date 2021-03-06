#source("http://bioconductor.org/biocLite.R")
#biocLite("VariantAnnotation", "GenomicFeatures")
#install.packages('reshape')
library(stringr)
library(reshape)
library(parallel)
library(plyr)
library(data.table)
library(VariantAnnotation)
library(GenomicFeatures)
library(testthat)


issymbolic <- function(vcf) {
  if (nrow(vcf) == 0) return(logical(0))
  v <- as.character(unstrsplit(alt(vcf)))
  return(str_detect(v, stringr::fixed("<")) | str_detect(v, stringr::fixed("[")) | str_detect(v, stringr::fixed("]")))
}
svlen <- function(vcf) {
  return(svlentype(vcf)$len)
}
svtype <- function(vcf) {
  return(svlentype(vcf)$type)
}
svlentype <- function(vcf) {
  if (nrow(vcf) == 0) return(list(len=integer(0), type=c()))
  sym <- issymbolic(vcf)
  # calculate from first alt allele
  alleleLen <- width(unlist(alt(vcf)))[c(1, 1 + head(cumsum(elementLengths(alt(vcf))), -1))]
  refLen <- elementLengths(ref(vcf))
  alleleSizeDiff <- alleleLen - refLen
  len=ifelse(sym, NA_integer_, ifelse(alleleLen != refLen, abs(alleleLen - refLen), alleleLen))
  # can't correctly classify events with no length change (INV, DUP, ...) for non-symbolic alleles
  type=ifelse(sym, NA_integer_, ifelse(alleleLen > refLen, "INS", ifelse(alleleLen < refLen, "DEL", "UNKNOWN")))
  
  # override with SV info column data
  svcol <- info(vcf)$SVLEN
  if (!is.null(svcol)) {
    # grab first allele
    len2 <- unlist(svcol)[c(1, 1 + head(cumsum(elementLengths(svcol)), -1))]
    len2[elementLengths(svcol) == 0] <- NA_integer_
    len <- ifelse(is.na(len2), len, len2)
  }
  if (!is.null(info(vcf)$END) & any(is.na(len))) {
    # Delly "INFO:SVLEN was a redundant tag because it's just INFO:END - POS for Delly. Since SVLEN is kind of ill-defined in the VCF-Specification I decided to remove it. If you need it for filtering just use end - start."
    len2 <- info(vcf)$END - start(rowRanges(vcf))
    len <- ifelse(is.na(len2), len, len2)
  }
  svcol <- info(vcf)$SVTYPE
  if (!is.null(svcol)) {
    type <- ifelse(is.na(svcol), type, svcol)
  }
  return(list(len=len, type=type))
}
breakendCount <- function(type) {
  typecounts <- ifelse(type %in% c("BND"), 1,
                ifelse(type %in% c("INS", "DEL", "DUP", "DUP:TANDEM", "TRA", "RPL"), 2,
                ifelse(type %in% c("INV"), 4,
                NA_integer_)))
  if (any(is.na(typecounts))) {
    stop(paste("Unhandled event type in ", paste(unique(type))))
  }
  return (typecounts)
}
# lists overlapping breakpoints
breakpointHits <- function(queryGr, subjectGr, mateQueryGr=queryGr[queryGr$mateIndex,], mateSubjectGr=subjectGr[subjectGr$mateIndex,], ...) {
  if (length(queryGr) != length(mateQueryGr)) {
    stop("queryGr, mateQueryGr have different lengths")
  }
  if (length(queryGr) != length(mateQueryGr)) {
    stop("subjectGr, mateSubjectGr have different lengths")
  }
  dfhits <- rbind(as.data.frame(findOverlaps(queryGr, subjectGr, ...), row.names=NULL),
                  as.data.frame(findOverlaps(mateQueryGr, mateSubjectGr, ...), row.names=NULL))
  dfhits <- dfhits[duplicated(dfhits),] # both breakends match
  return(dfhits)
}
# counts overlapping breakpoints
countBreakpointHits <- function(queryGr, subjectGr, mateQueryGr=queryGr[queryGr$mate,], mateSubjectGr=subjectGr[subjectGr$mate,], ...) {
  dfhits <- breakpointHits(queryGr, subjectGr, mateQueryGr, mateSubjectGr, ...)
  queryHits <- rep(0, length(queryGr))
  queryHits[count(dfhits, "queryHits")$queryHits] <- count(dfhits, "queryHits")$freq
  subjectHits <- rep(0, length(subjectGr))
  subjectHits[count(dfhits, "subjectHits")$subjectHits] <- count(dfhits, "subjectHits")$freq
  return(list(queryHitCount=queryHits, subjectHitCount=subjectHits))
}
isUnpairedBreakend <- function(vcf) {
  if (is.null(info(vcf)$SVTYPE)) {
    return(rep(FALSE, nrow(vcf)))
  }
  isbnd <- info(vcf)$SVTYPE=="BND"
  if (!is.null(info(vcf)$MATEID)) {
    isunpaired <- !(as.character(info(vcf)$MATEID) %in% rownames(vcf))
  } else if (!is.null(info(vcf)$PARID)) {
    isunpaired <- !(as.character(info(vcf)$PARID) %in% rownames(vcf))
  } else {
    isunpaired <- rep(TRUE, nrow(vcf))
  }
  return(isbnd & isunpaired)
}
isSV <- function(vcf) {
  lentype <- svlentype(vcf)
  # cortex calls SV SNPs
  snpType <- is.na(lentype$type) | lentype$type %in% c("SNP", "SNP_FROM_COMPLEX")
  validLen <- is.na(lentype$len) | lentype$len != 0
  result <- !snpType & validLen
  if (any(is.na(result))) {
    browser()
    stop()
  }
  return(result)
}
# converts a VCF to a GRanges containing the paired variant breakends with the following fields:
# vcfIndex: index of variant in VCF
# mateIndex: index of matching breakend in the GRanges object
# size: event size
# SVTYPE: type of event called
vcftobpgr <- function(vcf) {
  lentype <- svlentype(vcf)
  grcall <- rowRanges(vcf)
  grcall$vcfIndex <- seq_along(grcall)
  grcall$vcfid <- row.names(vcf)
  grcall$mateIndex <- rep(NA_integer_, length(grcall))
  grcall$SVTYPE <- lentype$type
  grcall$size <- lentype$len
  grcall$untemplated <- rep(NA_integer_, length(grcall))
  strand(grcall) <- rep("+", length(grcall))
  rows <- !issymbolic(vcf)
  if (!is.null(grcall$SVTYPE)) {
    rows <- rows & is.na(grcall$SVTYPE)
  }
  grcall$cistartoffset <- rep(0, length(grcall))
  grcall$ciwidth <- rep(0, length(grcall))
  if ("CIPOS" %in% names(info(vcf))) {
    # Expand call position by CIPOS
    offsets <- matrix(unlist(info(vcf)$CIPOS), ncol = 2, byrow = TRUE)
    offsets[is.na(offsets)] <- 0
    grcall$cistartoffset <- offsets[,1]
    grcall$ciwidth <- offsets[,2] - offsets[,1]
  }
  grcall$ciendstartoffset <- grcall$cistartoffset
  grcall$ciendwidth <- grcall$ciwidth
  grcall$isend <- rep(FALSE, length(grcall))
  if ("CIEND" %in% names(info(vcf))) {
    offsets <- matrix(unlist(info(vcf)$CIEND), ncol = 2, byrow = TRUE)
    offsets[is.na(offsets)] <- 0
    grcall$ciendstartoffset <- offsets[,1]
    grcall$ciendwidth <- offsets[,2] - offsets[,1]
  }
  if (any(rows)) {
    # non-symbolic VCF record
    browser()
    stop("TODO: handle non-symbolic alleles")
  }
  rows <- grcall$SVTYPE=="BND"
  if (any(rows)) {
    # set strand for BND
    bndMatches <- str_match(as.character(rowRanges(vcf[rows,])$ALT), "(.*)(\\[|])(.*)(\\[|])(.*)")
    preBases <- bndMatches[,2]
    bracket <- bndMatches[,3]
    remoteLocation <- bndMatches[,4]
    postBases <- bndMatches[,6]
    strand(grcall[rows,]) <- ifelse(str_length(preBases) > 0, "+", "-")
    if (!is.null(info(vcf)$MATEID)) {
      grcall[rows,]$mateIndex <- match(as.character(info(vcf)$MATEID[rows]), names(rowRanges(vcf)))
    } else if (!is.null(info(vcf)$PARID)) {
      grcall[rows,]$mateIndex <- match(info(vcf)$PARID[rows], names(rowRanges(vcf)))
    }
    grcall[rows,]$untemplated <- str_length(preBases) + str_length(postBases) - str_length(as.character(rowRanges(vcf)$REF)[rows])
    if (any(rows & is.na(grcall$mateIndex))) {
      warning(paste0("Unpaired breakends ", as.character(paste(names(grcall[is.na(grcall[rows,]$mateIndex),]), collapse=", "))))
      grcall[rows & is.na(grcall$mateIndex),]$mateIndex <- seq_along(grcall)[rows & is.na(grcall$mateIndex)]
    }
    mateBnd <- grcall[grcall[rows,]$mateIndex,]
    grcall[rows,]$size <- ifelse(seqnames(mateBnd)==seqnames(grcall[rows,]), abs(start(grcall[rows,]) - start(mateBnd)) - 1 + grcall[rows,]$untemplated, NA_integer_)
  }
  # non-standard event type used by DELLY
  rows <- grcall$SVTYPE=="TRA"
  if (any(rows)) {
    # TODO: recheck this matches the delly representation of the event
    strand(grcall[rows,]) <- ifelse(substr(info(vcf)$CT[rows], 1, 1) == "3", "+", "-")
    grcall[rows,]$size <- NA
    grcall[rows]$mateIndex <- length(grcall) + seq_len(sum(rows))
    eventgr <- grcall[rows]
    eventgr$mateIndex <- seq_len(length(grcall))[rows]
    # need to make new copy since seqlevels might not match
    bareeventgr <- GRanges(
      seqnames=info(vcf)$CHR2[rows],
      ranges=IRanges(start=info(vcf)$END[rows], width=1),
      strand=ifelse(substr(info(vcf)$CT[rows], 4, 4) == "3", "+", "-"))
    mcols(bareeventgr) <- mcols(eventgr)
    grcall <- c(grcall, bareeventgr)
  }
  rows <- grcall$SVTYPE=="DEL" |
    (grcall$SVTYPE %in% c("INDEL_FROM_COMPLEX", "INV_INDEL") & grcall$size < 0) # cortex
  if (any(rows)) {
    grcall[rows]$mateIndex <- length(grcall) + seq_len(sum(rows))
    eventgr <- grcall[rows]
    strand(eventgr) <- "-"
    eventgr$isend <- TRUE
    ranges(eventgr) <- IRanges(start=start(eventgr) + abs(grcall$size[rows]) + 1, width=1)
    eventgr$mateIndex <- seq_len(length(grcall))[rows]
    grcall <- c(grcall, eventgr)
  }
  rows <- grcall$SVTYPE=="INS" |
    (grcall$SVTYPE %in% c("INDEL_FROM_COMPLEX", "INV_INDEL") & grcall$size > 0)  # cortex
  if (any(rows)) {
    grcall[rows]$mateIndex <- length(grcall) + seq_len(sum(rows))
    eventgr <- grcall[rows]
    strand(eventgr) <- "-"
    eventgr$isend <- TRUE
    ranges(eventgr) <-IRanges(start=start(eventgr) + 1, width=1)
    eventgr$mateIndex <- seq_len(length(grcall))[rows]
    grcall <- c(grcall, eventgr)
  }
  rows <- grcall$SVTYPE=="INV"
  if (any(rows)) {
    grcall[rows]$mateIndex <- length(grcall) + seq_len(sum(rows))
    eventgr1 <- grcall[rows]
    eventgr2 <- grcall[rows]
    eventgr3 <- grcall[rows]
    strand(eventgr2) <- "-"
    strand(eventgr3) <- "-"
    eventgr2$isend <- TRUE
    eventgr3$isend <- TRUE
    ranges(eventgr1) <- IRanges(start=start(eventgr1) + abs(grcall[rows]$size), width=1)
    ranges(eventgr3) <- IRanges(start=start(eventgr3) + abs(grcall[rows]$size), width=1)
    eventgr1$mateIndex <- seq_len(length(grcall))[rows]
    eventgr2$mateIndex <- length(grcall) + length(eventgr1) + length(eventgr2) + seq_len(length(eventgr3))
    eventgr3$mateIndex <- length(grcall) + length(eventgr1) + seq_len(length(eventgr2))
    grcall <- c(grcall, eventgr1, eventgr2, eventgr3)
  }
  rows <- grcall$SVTYPE=="DUP" | grcall$SVTYPE=="DUP:TANDEM" 
  if (any(rows)) {
    # note: pindel tandem duplication includes the sequence being duplicated in the REF allele
    grcall[rows]$mateIndex <- length(grcall) + seq_len(sum(rows))
    eventgr <- grcall[rows]
    strand(grcall[rows]) <- "-"
    eventgr$isend <- TRUE
    ranges(eventgr) <- IRanges(start=start(eventgr) + abs(grcall[rows]$size), width=1)
    eventgr$mateIndex <- seq_len(length(grcall))[rows]
    grcall <- c(grcall, eventgr)
  }
  rows <- grcall$SVTYPE=="RPL"
  if (any(rows)) {
    # pindel 'replacement' SV type for handling untemplated sequence
    # place breakpoints at start and end of ref allele position
    # (not ideal, but it's the best conversion we can do without examine the actual sequences)
    grcall[rows]$mateIndex <- length(grcall) + seq_len(sum(rows))
    eventgr <- grcall[rows]
    strand(eventgr) <- "-"
    eventgr$isend <- TRUE
    ranges(eventgr) <- IRanges(start=start(eventgr) + abs(elementLengths(ref(vcf))[rows]), width=1)
    eventgr$mateIndex <- seq_len(length(grcall))[rows]
    grcall <- c(grcall, eventgr)
  }
  width(grcall) <- rep(1, length(grcall))
  grcall$callPosition <- start(grcall)
  start(grcall) <- start(grcall) + ifelse(grcall$isend, grcall$ciendstartoffset, grcall$cistartoffset)
  end(grcall) <- start(grcall) + ifelse(grcall$isend, grcall$ciendwidth, grcall$ciwidth)
  if (any(is.na(grcall$mateIndex))) {
    browser()
    stop(paste0("Unhandled SVTYPE ", unique(grcall$SVTYPE[is.na(grcall$mateIndex)])))
  }
  # Check the partner of the partner of each row is the row itself
  if (!all(grcall[grcall$mateIndex,]$mateIndex == seq_along(grcall))) {
    browser()
    stop("Breakends are not uniquely paired.")
  }
  if (any(grcall$mateIndex == seq_along(grcall))) {
    #browser()
    warning("Breakend has been partnered with itself - have all appropriate SV types been handled?")
  }
  return(grcall)
}
interval_distance <- function(s1, e1, s2, e2) {
  return (ifelse(s2 >= s1 & s2 <= e1, 0,
          ifelse(s1 >= s2 & s1 <= e2, 0,
          ifelse(s1 < s2, s2 - e1, s1 - e2))))
}
distanceToClosest <- function(query, subject) {
  distanceHits <- distanceToNearest(query, subject)
  result <- rep(NA, length(query))
  result[queryHits(distanceHits)] <- as.data.frame(distanceHits)$distance
  return(result)
}
CalculateTruth <- function(callvcf, truthvcf, blacklist=NULL, maxerrorbp, ignoreFilters, maxerrorpercent=0.25, errorpercentbpoffset=2*(1/maxerrorpercent), ...) {
  if (any(!is.na(rowRanges(callvcf)$QUAL) & rowRanges(callvcf)$QUAL < 0)) {
    stop("Precondition failure: variant exists with negative quality score")
  }
  if (!ignoreFilters) {
    callvcf <- callvcf[rowRanges(callvcf)$FILTER %in% c(".", "PASS"),]
    truthvcf <- truthvcf[rowRanges(truthvcf)$FILTER %in% c(".", "PASS"),]
  }
  if (!is.null(blacklist)) {
    callvcf <- callvcf[!endpointOverlapsBed(callvcf, blacklist, maxgap=maxerrorbp)]
    truthvcf <- truthvcf[!endpointOverlapsBed(truthvcf, blacklist, maxgap=maxerrorbp)]
  }
  grcall <- vcftobpgr(callvcf)
  grtruth <- vcftobpgr(truthvcf)
  
  hits <- breakpointHits(query=grcall, subject=grtruth, maxgap=maxerrorbp + max(0, grcall$ciwidth), ...)
  hits$QUAL <- grcall$QUAL[hits$queryHits]
  hits$QUAL[is.na(hits$QUAL)] <- 0
  hits$poserror <- interval_distance(start(grcall)[hits$queryHits], end(grcall)[hits$queryHits], start(grtruth)[hits$subjectHits], end(grtruth)[hits$subjectHits]) # breakend error distribution
  hits$calledsize <- abs(grcall$size[hits$queryHits])
  hits$expectedsize <- abs(grtruth$size[hits$subjectHits])
  hits$errorsize <- abs(abs(hits$calledsize - hits$expectedsize) - grcall$ciwidth[hits$queryHits])
  hits$percentsize <- (hits$calledsize + errorpercentbpoffset) / (hits$expectedsize + errorpercentbpoffset)
  # TODO: add untemplated into sizerror calculation for insertions
  hits <- hits[is.na(hits$poserror) | hits$poserror <= maxerrorbp, ] # position must be within margin
  if (!is.null(maxerrorpercent)) {
    # size must approximately match
    hits <- hits[is.na(hits$expectedsize) | is.na(hits$calledsize) | (hits$percentsize >= 1 - maxerrorpercent & hits$percentsize <= 1 + maxerrorpercent), ]
  }
  # TODO: filter mismatched event types (eg: DEL called for INS)
  
  # per truth variant
  hits <- data.table(hits, key="subjectHits")
  tdf <- hits[, list(hits=.N, poserror=min(poserror), errorsize=min(errorsize), QUAL=max(QUAL)), by="subjectHits"] 
  grtruth$QUAL <- -1
  grtruth$QUAL[tdf$subjectHits] <- tdf$QUAL
  grtruth$hits <- 0
  grtruth$hits[tdf$subjectHits] <- tdf$hits
  grtruth$poserror <- NA_integer_
  grtruth$poserror[tdf$subjectHits] <- tdf$poserror
  grtruth$errorsize <- NA_integer_
  grtruth$errorsize[tdf$subjectHits] <- tdf$errorsize
  grtruth$tp <- grtruth$hits > 0
  truthdf <- data.frame(
    vcfIndex=seq_along(rowRanges(truthvcf)),
    SVTYPE=svtype(truthvcf),
    SVLEN=svlen(truthvcf),
    expectedbehits=breakendCount(svtype(truthvcf))
    )
  tdfbe <- data.table(as.data.frame(mcols(grtruth)), key="vcfIndex")[, list(
    behits=sum(tp),
    poserror=mean(poserror),
    maxposerror=max(poserror),
    errorsize=min(errorsize),
    QUAL=mean(QUAL)
    ), by="vcfIndex"]
  if (any(truthdf$vcfIndex != tdfbe$vcfIndex)) {
    stop("Sanity check failure: tdfbe vcfIndex offsets do not match truthdf")
  }
  tdfbe$vcfIndex <- NULL
  truthdf <- cbind(truthdf, tdfbe)
  truthdf$tp <- truthdf$expectedbehits == truthdf$behits
  truthdf$partialtp <- !truthdf$tp & truthdf$behits > 0
  truthdf$fn <- !truthdf$tp
  
  # per variant call
  hits <- data.table(hits, key="queryHits")
  cdf <- hits[, list(hits=.N, poserror=min(poserror), errorsize=min(errorsize)), by="queryHits"]
  grcall$hits <- rep(0, length(grcall))
  grcall$hits[cdf$queryHits] <- cdf$hits
  grcall$poserror <- rep(NA_integer_, length(grcall))
  grcall$poserror[cdf$queryHits] <- cdf$poserror
  grcall$errorsize <- rep(NA_integer_, length(grcall))
  grcall$errorsize[cdf$queryHits] <- cdf$errorsize
  grcall$tp <- grcall$hits > 0
  calldf <- data.frame(
    vcfIndex=seq_along(rowRanges(callvcf)),
    QUAL=rowRanges(callvcf)$QUAL,
    SVTYPE=svtype(callvcf),
    SVLEN=svlen(callvcf),
    expectedbehits=breakendCount(svtype(callvcf))
  )
  #data.frame(mcols(grcall))
  cdfbe <- data.table(
    vcfIndex=mcols(grcall)$vcfIndex,
    tp=mcols(grcall)$tp,
    poserror=mcols(grcall)$poserror,
    errorsize=mcols(grcall)$errorsize
    , key="vcfIndex")[, list(
    behits=sum(tp),
    poserror=mean(poserror),
    errorsize=min(errorsize),
    maxposerror=max(poserror)
    ), by="vcfIndex"]
  if (any(calldf$vcfIndex != cdfbe$vcfIndex)) {
    stop("Sanity check failure: cdfbe vcfIndex offsets do not match calldf")
  }
  cdfbe$vcfIndex <- NULL
  calldf <- cbind(calldf, cdfbe)
  calldf$tp <- calldf$expectedbehits == calldf$behits
  calldf$partialtp <- !calldf$tp & calldf$behits > 0
  calldf$fp <- !calldf$tp
  result <- list(calls=calldf, truth=truthdf, vcf=callvcf, truthvcf=truthvcf)
  test_that("sanity check result counts", {
    expect_equal(sum(result$calls$expectedbehits), sum(breakendCount(info(result$vcf)$SVTYPE)))
    expect_equal(sum(result$truth$expectedbehits), sum(breakendCount(info(result$truthvcf)$SVTYPE)))
    # multiple calls matching the same event are both called as TPs
    # Also, a single call can match multiple truth events
    #expect_equal(sum(result$calls$tp) = sum(result$truth$tp))
    #expect_equal(sum(result$calls$behits), sum(result$truth$behits))
  })
  return(result)
}
# Calculate ROC curve. Units of matching are breakend counts so should be halved to get breakpoint counts
TruthSummaryToROC <- function(ts, bylist=c("CX_ALIGNER", "CX_ALIGNER_SOFTCLIP", "CX_CALLER", "CX_READ_DEPTH", "CX_READ_FRAGMENT_LENGTH", "CX_READ_LENGTH", "CX_REFERENCE_VCF_VARIANTS", "SVTYPE"), ignore.zero.calls=TRUE) {
  truthset <- ts$truth[, c(bylist, "QUAL", "expectedbehits", "tp", "fn"), with=FALSE]
  truthset$QUAL[is.na(truthset$QUAL)] <- -1
  truthset$tp <- ifelse(truthset$tp, truthset$expectedbehits, 0)
  truthset$fn <- ifelse(!truthset$tp, truthset$expectedbehits, 0)
  truthset$fp <- 0
  truthset$expectedbehits <- NULL
  callset <- ts$calls[!ts$calls$tp,][, c(bylist, "QUAL", "expectedbehits", "fp"), with=FALSE]
  callset$QUAL[is.na(callset$QUAL)] <- 0
  callset$fp <- callset$expectedbehits
  callset$tp <- 0 # since we have removed them since they're in truthset as well
  callset$fn <- 0
  callset$expectedbehits <- NULL
  combined <- rbind(callset, truthset)
  combined$tp <- as.integer(combined$tp)
  combined$fn <- as.integer(combined$fn)
  combined$fp <- as.integer(combined$fp)
  setkeyv(combined, bylist)
  # aggregate from high to low
  combined <- combined[order(-combined$QUAL),]
  combined[,`:=`(ntruth=sum(tp)+sum(fn), ncalls=sum(tp)+sum(fp), tp=cumsum(tp), fp=cumsum(fp), fn=cumsum(fn), QUAL=cummin(QUAL)), by=bylist]
  combined <- combined[!duplicated(combined[, c(bylist, "QUAL"), with=FALSE], fromLast=TRUE),] # take only one data point per QUAL
  combined[,`:=`(sens=tp/ntruth, prec=tp/(tp+fp), fdr=fp/(tp+fp))]
  test_that("sanity check", {
    allcalls <- combined[order(combined$QUAL),]
    allcalls <- allcalls[!duplicated(allcalls, by=bylist),]
    expect_equal(allcalls$tp + allcalls$fp, allcalls$ncalls)
    expect_equal(allcalls$tp + allcalls$fn, allcalls$ntruth)
    expect_equal(sum(allcalls$tp), sum(ts$truth[ts$truth$tp]$behits))
    # multiple calls matching the same event will result in different truth counts for calls, and truth
    #expect_equal(sum(allcalls$tp), sum(ts$calls[ts$calls$tp]$behits))
    expect_equal(sum(allcalls$fp), sum(ts$calls[!ts$calls$tp]$expectedbehits))
    expect_equal(sum(allcalls$fn), sum(ts$truth[!ts$truth$tp]$expectedbehits))
  })
  if (ignore.zero.calls) {
    # ignore empty VCFs as these were likely due to variant caller crash
    combined <- combined[combined$ncalls > 0,]
  }
  return(combined)
}
FilterOutSNV <- function(vcf, caller) {
  if (any(elementLengths(alt(z)) != 1)) {
    browser()
    stop(paste("Analysis not designed for multiple alleles in a single VCF record.", filename))
  }
  # TODO: remove SNPs
  # - same length
  # - differ by 1 base (samtools will sometimes call a SNP as AAAC vs AAAT)
  # - not symbolic (no < or [ )
  #if (caller <- c("gatk")) {
  #  # strip SNPs based on alt allele length matching ref allele
  #  vcf <- vcf[elementLengths(stringSet)]
  #  vcf <- vcf[unlist(lapply(alt(vcf), function (stringSet) { elementLengths(stringSet)[1] } )) != elementLengths(ref(vcf))]
  #}
  return(vcf)
}

# ensures all records have a matching mate
ensure_bp_mate <- function(gr) {
  if (is.null(names(gr))) {
    stop("both query and subject must have names defined")
  }
  if (is.null(gr$mate) && !is.null(gr$mateIndex)) {
    gr$mate <- names(gr)[gr$mateIndex]
  }
  if (is.null(gr$mate)) {
    stop("both query and subject must have mate defined")
  }
  return(gr)
}
# finds putative short SV fragments
FindFragments <- function(gr, maxfragmentsize=500) {
  gr <- ensure_bp_mate(gr)
  # find all fragments [(start_local, end_local) pairings] in subject
  hits <- findOverlaps(gr, gr, ignore.strand=TRUE, maxgap=maxfragmentsize)
  hits <- hits[queryHits(hits) != subjectHits(hits) &
                 strand(gr[queryHits(hits),]) == '-' &
                 strand(gr[subjectHits(hits),]) == "+" &
                 end(gr[queryHits(hits),]) < start(gr[subjectHits(hits),]),]
  fragments <- data.frame(
    start_remote=gr[names(gr)[queryHits(hits)],]$mate,
    start_local=names(gr)[queryHits(hits)],
    end_remote=gr[names(gr)[subjectHits(hits)],]$mate,
    end_local=names(gr)[subjectHits(hits)],
    size=(start(gr[subjectHits(hits),]) + end(gr[subjectHits(hits),]) - start(gr[queryHits(hits),]) - end(gr[queryHits(hits),])) / 2,
    maxsize=end(gr[subjectHits(hits),]) - start(gr[queryHits(hits),]),
    minsize=start(gr[subjectHits(hits),]) - end(gr[queryHits(hits),]),
    stringsAsFactors=FALSE
  )
  return(fragments)
}
# Identifies query A-C calls that are actually intervening A-B-C calls according
# to the subject
#      _______
#  ___/       \____
# aaaaa-bbbb-ccccccc
#     A B  D C
# find query AC such that subject start_remote end_local exists for fragment B-D
FindFragmentSpanningEvents <- function(querygr, subjectgr, maxfragmentsize=500, maxgap=100) {
  querygr <- ensure_bp_mate(querygr)
  subjectgr <- ensure_bp_mate(subjectgr)
  # find all fragments [(start_local, end_local) pairings] in subject
  fragments <- FindFragments(subjectgr, maxfragmentsize + maxgap)
  startHits <- findOverlaps(querygr, subjectgr[fragments$start_remote,], ignore.strand=FALSE, maxgap=maxgap)
  endHits <- findOverlaps(querygr[querygr$mate,], subjectgr[fragments$end_remote,], ignore.strand=FALSE, maxgap=maxgap)
  spanningHits <- rbind(as.data.frame(startHits), as.data.frame(endHits))
  spanningHits <- spanningHits[duplicated(spanningHits),] # require an start_remote match and a end_remote match
  
  return(data.frame(
    query=names(querygr)[spanningHits$queryHits],
    subjectAlt1=fragments$start_remote[spanningHits$subjectHits],
    subjectAlt2=fragments$end_remote[spanningHits$subjectHits],
    size=fragments$size[spanningHits$subjectHits],
    simpleEvent=seqnames(querygr[spanningHits$queryHits,])==seqnames(querygr[querygr$mate,][spanningHits$queryHits,]) & abs(start(querygr[spanningHits$queryHits,]) - start(querygr[querygr$mate,][spanningHits$queryHits,])) < maxfragmentsize,
    stringsAsFactors=FALSE
  ))
}
test_that("spanning events identified", {
  querygr <- GRanges(seqnames=c('chr1','chr1'), IRanges(start=c(10, 20), width=1), strand=c('+', '-'))
  names(querygr) <- c("AC", "CA")
  querygr$mate <- reverse(names(querygr))
  subjectgr <- GRanges(
    seqnames=c('chr1','chr2','chr2','chr1'),
    IRanges(start=c(10, 100, 200, 20), width=1),
    strand=c('+', '-', '+', '-'))
  names(subjectgr) <- c("AB", "BA", "DC", "CD")
  subjectgr$mate <- reverse(names(subjectgr))
  expect_equal(data.frame(
    query=c("AC"),
    subjectAlt1=c("AB"),
    subjectAlt2=c("CD"),
    size=c(100),
    simpleEvent=c(TRUE),
    stringsAsFactors=FALSE
    ), FindFragmentSpanningEvents(querygr, subjectgr))
})
test_that("interchromsomal event not considered simple", {
  querygr <- GRanges(seqnames=c('chr1','chr3'), IRanges(start=c(10, 20), width=1), strand=c('+', '-'))
  names(querygr) <- c("AC", "CA")
  querygr$mate <- reverse(names(querygr))
  subjectgr <- GRanges(
    seqnames=c('chr1','chr2','chr2','chr3'),
    IRanges(start=c(10, 100, 200, 20), width=1),
    strand=c('+', '-', '+', '-'))
  names(subjectgr) <- c("AB", "BA", "DC", "CD")
  subjectgr$mate <- reverse(names(subjectgr))
  expect_equal(data.frame(
    query=c("AC"),
    subjectAlt1=c("AB"),
    subjectAlt2=c("CD"),
    size=c(100),
    simpleEvent=c(FALSE),
    stringsAsFactors=FALSE
  ), FindFragmentSpanningEvents(querygr, subjectgr))
})

bedpe2grpair <- function(filename, header=FALSE) {
  tsv <- read.csv(filename, sep="\t", stringsAsFactors=FALSE, header=FALSE)
  names(tsv) <- c("chrom1", "start1", "end1", "chrom2", "start2", "end2", "name", "score", "strand1", "strand2", tail(names(tsv), -10))
  gr1 <- GRanges(seqnames=tsv$chrom1, ranges=IRanges(start=tsv$start1, end=tsv$end1, name=tsv$name), strand=tsv$strand1, tsv[c(8, seq(from=11, length.out=length(tsv) - 10))])
  gr2 <- GRanges(seqnames=tsv$chrom2, ranges=IRanges(start=tsv$start2, end=tsv$end2, name=tsv$name), strand=tsv$strand2, tsv[c(8, seq(from=11, length.out=length(tsv) - 10))])
  return(list(gr1=gr1, gr2=gr2))
}
bedpe2grmate <- function(filename, header=FALSE) {
  out <- bedpe2grpair(filename, header)
  out$gr1$mate <- paste0(names(out$gr1), "/2")
  names(out$gr1) <- paste0(names(out$gr1), "/1")
  out$gr2$mate <- paste0(names(out$gr2), "/1")
  names(out$gr2) <- paste0(names(out$gr2), "/2")
  return(c(out$gr1, out$gr2))
}
# infer proxy quality scores for ROC purposes based on strength of support
withqual <- function(vcf, caller) {
  if (is.null(rowRanges(vcf)$QUAL)) {
    rowRanges(vcf)$QUAL <- NA_real_
  }
  if (!is.na(caller) && !is.null(caller) && all(is.na(rowRanges(vcf)$QUAL))) {
    caller <- str_extract(caller, "^[^/]+") # strip version
    # use total read support as a qual proxy
    if (caller %in% c("delly")) {
      rowRanges(vcf)$QUAL <- ifelse(is.na(info(vcf)$PE), 0, info(vcf)$PE) + ifelse(is.na(info(vcf)$SR), 0, info(vcf)$SR)
    } else if (caller %in% c("crest")) {
      rowRanges(vcf)$QUAL <- ifelse(is.na(info(vcf)$right_softclipped_read_count), 0, info(vcf)$right_softclipped_read_count) + ifelse(is.na(info(vcf)$left_softclipped_read_count), 0, info(vcf)$left_softclipped_read_count)
    } else if (caller %in% c("pindel")) {
      rowRanges(vcf)$QUAL <- geno(vcf)$AD[,1,2]
    } else if (caller %in% c("lumpy")) {
      rowRanges(vcf)$QUAL <- unlist(info(vcf)$SU)
    } else if (caller %in% c("cortex")) {
      # TODO: does cortex ever call multiple alleles for a single site for a single sample?
      # eg het B/C when neither B nor B match REF allele A
      rowRanges(vcf)$QUAL <- geno(vcf)$COV[,1,2]
    }
  }
  if (any(is.na(rowRanges(vcf)$QUAL))) {
    warning(paste("Missing QUAL scores for", caller))
  }
  return(vcf)
}
isInterChromosmal <- function(vcf) {
  gr <- vcftobpgr(vcf)
  gri <- gr[!duplicated(gr$vcfIndex),]
  gr <- gr[seqnames(gr) == seqnames(gr[gr$mateIndex,]),]
  return(seqnames(gri) != seqnames(gr[gri$mateIndex]))
}
cleanCortex <- function(vcf) {
  vcf <- vcf[isSV(vcf),]
  info(vcf)$SVTYPE[info(vcf)$SVTYPE %in% c("INDEL_FROM_COMPLEX", "INV_INDEL") & info(vcf)$SVLEN > 0 ] <- "INS"
  info(vcf)$SVTYPE[info(vcf)$SVTYPE %in% c("INDEL_FROM_COMPLEX", "INV_INDEL") & info(vcf)$SVLEN < 0 ] <- "DEL"
  return(vcf)
}
# Event looks like a deletion.
# this does not yet handle multi-breakpoint events reported in BND format (eg gridss translocations)
isDeletionLike <- function(vcf, minsize=0) {
  lentype <- svlentype(vcf)
  len <- abs(lentype$len)
  type <- lentype$type
  gr <- vcftobpgr(vcf)
  gri <- gr[!duplicated(gr$vcfIndex),]
  return(seqnames(gri) == seqnames(gr[gri$mateIndex]) & 
      ((type == "DEL" & len >= minsize) | (type == "BND" & abs(start(gri) - start(gr[gri$mateIndex])) > minsize & (
        (strand(gri) == "+" & strand(gr[gri$mateIndex]) == "-" & start(gri) < start(gr[gri$mateIndex])) |
        (strand(gri) == "-" & strand(gr[gri$mateIndex]) == "+" & start(gri) > start(gr[gri$mateIndex]))))))
}
eventSize <- function(vcf) {
  gr <- vcftobpgr(vcf)
  gri <- gr[!duplicated(gr$vcfIndex),]
  return(ifelse(seqnames(gri) == seqnames(gr[gri$mateIndex]), abs(start(gri) - start(gr[gri$mateIndex])), NA_integer_))
}
# add chr prefix to genomic ranges
withChr <- function(gr) {
  seqlevels(gr) <- ifelse(str_detect(seqlevels(gr), "chr"), seqlevels(gr), paste0("chr", seqlevels(gr)))
  return(gr)
}
endpointOverlapsBed <- function(vcf, bed, ...) {
  gr <- vcftobpgr(vcf)
  gr$overlaps <- overlapsAny(GRanges(seqnames=seqnames(gr), ranges=IRanges(start=start(gr), width=1)), blacklist, type="any", ...) |
    overlapsAny(GRanges(seqnames=seqnames(gr), ranges=IRanges(end=end(gr), width=1)), blacklist, type="any", ...)
  result <- rep(FALSE, nrow(vcf))
  result[gr[gr$overlaps]$vcfIndex] <- TRUE
  return(result)
}


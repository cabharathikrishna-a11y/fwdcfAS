package com.example.api

enum class CAInterSubject(val subjectCode: String, val title: String, val totalMarks: Int) {
    PAPER_1("P1", "Paper 1: Advanced Accounting", 100),
    PAPER_2A("P2A", "Paper 2A: Corporate Law & LLP", 70),
    PAPER_2B("P2B", "Paper 2B: Other Laws", 30),
    PAPER_3A("P3A", "Paper 3A: Income Tax Law", 50),
    PAPER_3B("P3B", "Paper 3B: Goods and Services Tax (GST)", 50),
    PAPER_4("P4", "Paper 4: Cost and Management Accounting", 100),
    PAPER_5("P5", "Paper 5: Auditing and Ethics", 100),
    PAPER_6A("P6A", "Paper 6A: Financial Management", 50),
    PAPER_6B("P6B", "Paper 6B: Strategic Management", 50);

    companion object {
        fun fromCode(code: String): CAInterSubject {
            return entries.firstOrNull { code.startsWith(it.subjectCode) } ?: PAPER_1
        }
    }
}

data class SyllabusTopicNode(
    val topicId: String,          // Unique ID (e.g., "P3B_CH3_ITC")
    val subject: CAInterSubject,
    val chapterName: String,      // e.g., "Ch 3: Basic Concepts of GST"
    val subTopicTitle: String     // e.g., "Input Tax Credit"
)

object SyllabusRegistry {
    val allTopics = listOf(
        // ================= PAPER 1: ADVANCED ACCOUNTING (100M) =================
        SyllabusTopicNode("P1_CH1_FRM", CAInterSubject.PAPER_1, "Ch 1: Accounting Standards Framework", "AS Formulation, IFRS Convergence & Carve outs"),
        SyllabusTopicNode("P1_CH1_PREP", CAInterSubject.PAPER_1, "Ch 1: Accounting Standards Framework", "Framework for Preparation & Presentation of FS"),
        SyllabusTopicNode("P1_AS1", CAInterSubject.PAPER_1, "Ch 2: Application of Accounting Standards", "AS 1: Disclosure of Accounting Policies"),
        SyllabusTopicNode("P1_AS2", CAInterSubject.PAPER_1, "Ch 2: Application of Accounting Standards", "AS 2: Valuation of Inventories"),
        SyllabusTopicNode("P1_AS3", CAInterSubject.PAPER_1, "Ch 2: Application of Accounting Standards", "AS 3: Cash Flow Statements"),
        SyllabusTopicNode("P1_AS4", CAInterSubject.PAPER_1, "Ch 2: Application of Accounting Standards", "AS 4: Contingencies & Events After BS Date"),
        SyllabusTopicNode("P1_AS5", CAInterSubject.PAPER_1, "Ch 2: Application of Accounting Standards", "AS 5: Net Profit/Loss, Prior Period & Policies"),
        SyllabusTopicNode("P1_AS7", CAInterSubject.PAPER_1, "Ch 2: Application of Accounting Standards", "AS 7: Construction Contracts"),
        SyllabusTopicNode("P1_AS9", CAInterSubject.PAPER_1, "Ch 2: Application of Accounting Standards", "AS 9: Revenue Recognition"),
        SyllabusTopicNode("P1_AS10", CAInterSubject.PAPER_1, "Ch 2: Application of Accounting Standards", "AS 10: Property, Plant and Equipment"),
        SyllabusTopicNode("P1_AS11", CAInterSubject.PAPER_1, "Ch 2: Application of Accounting Standards", "AS 11: Foreign Exchange Rates"),
        SyllabusTopicNode("P1_AS12", CAInterSubject.PAPER_1, "Ch 2: Application of Accounting Standards", "AS 12: Government Grants"),
        SyllabusTopicNode("P1_AS13", CAInterSubject.PAPER_1, "Ch 2: Application of Accounting Standards", "AS 13: Accounting for Investments"),
        SyllabusTopicNode("P1_AS14", CAInterSubject.PAPER_1, "Ch 2: Application of Accounting Standards", "AS 14: Amalgamations"),
        SyllabusTopicNode("P1_AS15", CAInterSubject.PAPER_1, "Ch 2: Application of Accounting Standards", "AS 15: Employee Benefits"),
        SyllabusTopicNode("P1_AS16", CAInterSubject.PAPER_1, "Ch 2: Application of Accounting Standards", "AS 16: Borrowing Costs"),
        SyllabusTopicNode("P1_AS17", CAInterSubject.PAPER_1, "Ch 2: Application of Accounting Standards", "AS 17: Segment Reporting"),
        SyllabusTopicNode("P1_AS18", CAInterSubject.PAPER_1, "Ch 2: Application of Accounting Standards", "AS 18: Related Party Disclosures"),
        SyllabusTopicNode("P1_AS19", CAInterSubject.PAPER_1, "Ch 2: Application of Accounting Standards", "AS 19: Leases"),
        SyllabusTopicNode("P1_AS20", CAInterSubject.PAPER_1, "Ch 2: Application of Accounting Standards", "AS 20: Earnings Per Share"),
        SyllabusTopicNode("P1_AS21", CAInterSubject.PAPER_1, "Ch 2: Application of Accounting Standards", "AS 21: Consolidated Financial Statements"),
        SyllabusTopicNode("P1_AS22", CAInterSubject.PAPER_1, "Ch 2: Application of Accounting Standards", "AS 22: Taxes on Income"),
        SyllabusTopicNode("P1_AS23", CAInterSubject.PAPER_1, "Ch 2: Application of Accounting Standards", "AS 23: Investment in Associates in CFS"),
        SyllabusTopicNode("P1_AS24", CAInterSubject.PAPER_1, "Ch 2: Application of Accounting Standards", "AS 24: Discontinuing Operations"),
        SyllabusTopicNode("P1_AS25", CAInterSubject.PAPER_1, "Ch 2: Application of Accounting Standards", "AS 25: Interim Financial Reporting"),
        SyllabusTopicNode("P1_AS26", CAInterSubject.PAPER_1, "Ch 2: Application of Accounting Standards", "AS 26: Intangible Assets"),
        SyllabusTopicNode("P1_AS27", CAInterSubject.PAPER_1, "Ch 2: Application of Accounting Standards", "AS 27: Interests in Joint Ventures"),
        SyllabusTopicNode("P1_AS28", CAInterSubject.PAPER_1, "Ch 2: Application of Accounting Standards", "AS 28: Impairment of Assets"),
        SyllabusTopicNode("P1_AS29", CAInterSubject.PAPER_1, "Ch 2: Application of Accounting Standards", "AS 29: Provisions, Contingent Liabilities & Assets"),
        SyllabusTopicNode("P1_CH3_SCH3", CAInterSubject.PAPER_1, "Ch 3: Company Accounts", "Schedule III (Division I) - FS Preparation"),
        SyllabusTopicNode("P1_CH3_BUY", CAInterSubject.PAPER_1, "Ch 3: Company Accounts", "Buy back of securities"),
        SyllabusTopicNode("P1_CH3_REC", CAInterSubject.PAPER_1, "Ch 3: Company Accounts", "Accounting for Reconstruction of Companies"),
        SyllabusTopicNode("P1_CH4_BR", CAInterSubject.PAPER_1, "Ch 4: Branch Accounting", "Accounting for Branches including Foreign Branches"),

        // ================= PAPER 2A: CORPORATE LAW & LLP (70M) =================
        SyllabusTopicNode("P2A_CH1", CAInterSubject.PAPER_2A, "Ch 1: Preliminary", "Important Definitions & Applicability"),
        SyllabusTopicNode("P2A_CH2", CAInterSubject.PAPER_2A, "Ch 2: Incorporation of Company", "Incorporation and Matters Incidental thereto"),
        SyllabusTopicNode("P2A_CH3", CAInterSubject.PAPER_2A, "Ch 3: Prospectus and Allotment", "Prospectus and Allotment of Securities"),
        SyllabusTopicNode("P2A_CH4", CAInterSubject.PAPER_2A, "Ch 4: Share Capital and Debentures", "Share Capital, Voting Rights and Debentures"),
        SyllabusTopicNode("P2A_CH5", CAInterSubject.PAPER_2A, "Ch 5: Acceptance of Deposits", "Acceptance of Deposits by Companies"),
        SyllabusTopicNode("P2A_CH6", CAInterSubject.PAPER_2A, "Ch 6: Registration of Charges", "Registration & Satisfaction of Charges"),
        SyllabusTopicNode("P2A_CH7", CAInterSubject.PAPER_2A, "Ch 7: Management & Administration", "Registers, Annual Returns & General Meetings"),
        SyllabusTopicNode("P2A_CH8", CAInterSubject.PAPER_2A, "Ch 8: Declaration of Dividend", "Declaration and Payment of Dividend"),
        SyllabusTopicNode("P2A_CH9", CAInterSubject.PAPER_2A, "Ch 9: Accounts of Companies", "Maintenance of Books, CSR & Financial Statements"),
        SyllabusTopicNode("P2A_CH10", CAInterSubject.PAPER_2A, "Ch 10: Audit and Auditors", "Appointment, Powers, Duties & Rotation of Auditors"),
        SyllabusTopicNode("P2A_CH11", CAInterSubject.PAPER_2A, "Ch 11: Foreign Companies", "Companies Incorporated Outside India"),
        SyllabusTopicNode("P2A_CH12", CAInterSubject.PAPER_2A, "Ch 12: Limited Liability Partnership Act, 2008", "LLP Act, 2008 & Important Rules"),

        // ================= PAPER 2B: OTHER LAWS (30M) =================
        SyllabusTopicNode("P2B_CH1", CAInterSubject.PAPER_2B, "Ch 1: The General Clauses Act, 1897", "Definitions, Construction & Powers"),
        SyllabusTopicNode("P2B_CH2", CAInterSubject.PAPER_2B, "Ch 2: Interpretation of Statutes", "Rules of Interpretation, Aids & Construction of Deeds"),
        SyllabusTopicNode("P2B_CH3", CAInterSubject.PAPER_2B, "Ch 3: FEMA, 1999", "Current and Capital Account Transactions"),

        // ================= PAPER 3A: INCOME TAX LAW (50M) =================
        SyllabusTopicNode("P3A_CH1_BAS", CAInterSubject.PAPER_3A, "Ch 1: Basic Concepts", "Introduction, Assessee, Person & Basis of Charge"),
        SyllabusTopicNode("P3A_CH1_COMP", CAInterSubject.PAPER_3A, "Ch 1: Basic Concepts", "Computation of Total Income & Tax Payable (Individuals)"),
        SyllabusTopicNode("P3A_CH2_RES", CAInterSubject.PAPER_3A, "Ch 2: Residential Status", "Residential Status & Scope of Total Income"),
        SyllabusTopicNode("P3A_CH3_SAL", CAInterSubject.PAPER_3A, "Ch 3: Heads of Income", "Salaries"),
        SyllabusTopicNode("P3A_CH3_HP", CAInterSubject.PAPER_3A, "Ch 3: Heads of Income", "Income from House Property"),
        SyllabusTopicNode("P3A_CH3_PGBP", CAInterSubject.PAPER_3A, "Ch 3: Heads of Income", "Profits and Gains of Business or Profession (PGBP)"),
        SyllabusTopicNode("P3A_CH3_CG", CAInterSubject.PAPER_3A, "Ch 3: Heads of Income", "Capital Gains"),
        SyllabusTopicNode("P3A_CH3_IFOS", CAInterSubject.PAPER_3A, "Ch 3: Heads of Income", "Income from Other Sources"),
        SyllabusTopicNode("P3A_CH4_CLUB", CAInterSubject.PAPER_3A, "Ch 4: Clubbing, Set-off & Deductions", "Clubbing of Income & Set-off / Carry Forward of Losses"),
        SyllabusTopicNode("P3A_CH4_DED", CAInterSubject.PAPER_3A, "Ch 4: Clubbing, Set-off & Deductions", "Deductions from Gross Total Income (Chapter VI-A)"),
        SyllabusTopicNode("P3A_CH5_ADV", CAInterSubject.PAPER_3A, "Ch 5: Advance Tax, TDS & TCS", "Advance Tax, Tax Deduction at Source & TCS"),
        SyllabusTopicNode("P3A_CH6_RET", CAInterSubject.PAPER_3A, "Ch 6: Return of Income", "Provisions for Filing Return & Self-Assessment"),
        SyllabusTopicNode("P3A_CH7_ALT", CAInterSubject.PAPER_3A, "Ch 7: Alternative Tax Regimes", "Computation under Alternative Tax Regimes (Sec 115BAC)"),

        // ================= PAPER 3B: GOODS AND SERVICES TAX (50M) =================
        SyllabusTopicNode("P3B_CH1_INT", CAInterSubject.PAPER_3B, "Ch 1: GST Introduction", "GST Laws: Introduction & Constitutional Aspects"),
        SyllabusTopicNode("P3B_CH2_LEV", CAInterSubject.PAPER_3B, "Ch 2: Levy and Collection", "Supply, Charge of Tax, Reverse Charge & Exemption"),
        SyllabusTopicNode("P3B_CH2_COMP", CAInterSubject.PAPER_3B, "Ch 2: Levy and Collection", "Composition Levy Scheme"),
        SyllabusTopicNode("P3B_CH3_CLS", CAInterSubject.PAPER_3B, "Ch 3: Basic Concepts of GST", "Classification, Place of Supply & Time of Supply"),
        SyllabusTopicNode("P3B_CH3_VAL", CAInterSubject.PAPER_3B, "Ch 3: Basic Concepts of GST", "Value of Supply"),
        SyllabusTopicNode("P3B_CH3_ITC", CAInterSubject.PAPER_3B, "Ch 3: Basic Concepts of GST", "Input Tax Credit (ITC)"),
        SyllabusTopicNode("P3B_CH4_LIAB", CAInterSubject.PAPER_3B, "Ch 4: Computation of GST Liability", "Computation of Net GST Liability"),
        SyllabusTopicNode("P3B_CH5_REG", CAInterSubject.PAPER_3B, "Ch 5: Registration", "Registration Procedures & Amendments under GST"),
        SyllabusTopicNode("P3B_CH6_INV", CAInterSubject.PAPER_3B, "Ch 6: Tax Invoice & E-Way Bill", "Tax Invoice, Credit/Debit Notes & Electronic Way Bill"),
        SyllabusTopicNode("P3B_CH7_ACC", CAInterSubject.PAPER_3B, "Ch 7: Accounts and Records", "Maintenance of Accounts and Records"),
        SyllabusTopicNode("P3B_CH8_RET", CAInterSubject.PAPER_3B, "Ch 8: Returns", "Filing of GST Returns"),
        SyllabusTopicNode("P3B_CH9_PAY", CAInterSubject.PAPER_3B, "Ch 9: Payment of Tax", "Payment of Tax, Interest, Penalty & Refunds"),

        // ================= PAPER 4: COST & MANAGEMENT ACCOUNTING (100M) =================
        SyllabusTopicNode("P4_CH1_OVW", CAInterSubject.PAPER_4, "Ch 1: Cost Overview & Cost Sheets", "Cost Terms, Elements of Cost & Cost Sheet Preparation"),
        SyllabusTopicNode("P4_CH2_MAT", CAInterSubject.PAPER_4, "Ch 2: Cost Ascertainment & Systems", "Material Cost (EOQ, Inventory Control, GeM, ERP)"),
        SyllabusTopicNode("P4_CH2_EMP", CAInterSubject.PAPER_4, "Ch 2: Cost Ascertainment & Systems", "Employee Cost (Turnover, Halsey & Rowan Plans)"),
        SyllabusTopicNode("P4_CH2_OVH", CAInterSubject.PAPER_4, "Ch 2: Cost Ascertainment & Systems", "Overheads (Absorption, Primary/Secondary Distribution)"),
        SyllabusTopicNode("P4_CH2_ABC", CAInterSubject.PAPER_4, "Ch 2: Cost Ascertainment & Systems", "Activity Based Costing (ABC)"),
        SyllabusTopicNode("P4_CH2_REC", CAInterSubject.PAPER_4, "Ch 2: Cost Ascertainment & Systems", "Cost & Financial Accounts Integration and Reconciliation"),
        SyllabusTopicNode("P4_CH3_UNIT", CAInterSubject.PAPER_4, "Ch 3: Methods of Costing", "Single Output, Unit, Job and Batch Costing"),
        SyllabusTopicNode("P4_CH3_PROC", CAInterSubject.PAPER_4, "Ch 3: Methods of Costing", "Process Costing, Joint Products and By-Products"),
        SyllabusTopicNode("P4_CH3_SERV", CAInterSubject.PAPER_4, "Ch 3: Methods of Costing", "Service Sector Costing"),
        SyllabusTopicNode("P4_CH4_STD", CAInterSubject.PAPER_4, "Ch 4: Cost Control and Analysis", "Standard Costing (Material, Labour & Overhead Variances)"),
        SyllabusTopicNode("P4_CH4_MARG", CAInterSubject.PAPER_4, "Ch 4: Cost Control and Analysis", "Marginal Costing, CVP Analysis & Short-Term Decisions"),
        SyllabusTopicNode("P4_CH4_BUD", CAInterSubject.PAPER_4, "Ch 4: Cost Control and Analysis", "Budget & Budgetary Control (Flexible, Cash, Master & ZBB)"),

        // ================= PAPER 5: AUDITING AND ETHICS (100M) =================
        SyllabusTopicNode("P5_CH1_NAT", CAInterSubject.PAPER_5, "Ch 1: Nature, Objective & Scope of Audit", "Auditing Concepts, Qualities of Auditor & SA 200"),
        SyllabusTopicNode("P5_CH2_STR", CAInterSubject.PAPER_5, "Ch 2: Strategy, Planning & Program", "Audit Planning, Strategy & Program (SA 300)"),
        SyllabusTopicNode("P5_CH3_RISK", CAInterSubject.PAPER_5, "Ch 3: Risk Assessment & Internal Control", "Risk of Material Misstatement, IC Evaluation & SA 315"),
        SyllabusTopicNode("P5_CH3_DIG", CAInterSubject.PAPER_5, "Ch 3: Risk Assessment & Internal Control", "Digital Audit, Automated Environment & SA 330"),
        SyllabusTopicNode("P5_CH4_EVID", CAInterSubject.PAPER_5, "Ch 4: Audit Evidence", "Audit Evidence (SA 500), Sampling (SA 530) & Confirmations (SA 505)"),
        SyllabusTopicNode("P5_CH4_SPEC", CAInterSubject.PAPER_5, "Ch 4: Audit Evidence", "Opening Balances (SA 510), Related Parties (SA 550) & Analytical (SA 520)"),
        SyllabusTopicNode("P5_CH4_INT", CAInterSubject.PAPER_5, "Ch 4: Audit Evidence", "Using Work of Internal Auditors (SA 610) & Audit Trail"),
        SyllabusTopicNode("P5_CH5_FS", CAInterSubject.PAPER_5, "Ch 5: Audit of Financial Statement Items", "Audit of BS and P&L Items (Assets, Liabilities, Revenue & Expenses)"),
        SyllabusTopicNode("P5_CH6_DOC", CAInterSubject.PAPER_5, "Ch 6: Audit Documentation", "Nature, Purpose & Custody of Documentation (SA 230)"),
        SyllabusTopicNode("P5_CH7_COMP", CAInterSubject.PAPER_5, "Ch 7: Completion and Review", "Subsequent Events (SA 560), Going Concern (SA 570) & Written Reps (SA 580)"),
        SyllabusTopicNode("P5_CH8_REP", CAInterSubject.PAPER_5, "Ch 8: Audit Report", "Forming Opinion (SA 700), KAM (SA 701), Modifications (SA 705/706) & CARO"),
        SyllabusTopicNode("P5_CH9_SPEC", CAInterSubject.PAPER_5, "Ch 9: Special Features of Audit", "Audit of Gov, Local Bodies, NPOs, Educational Institutions & LLPs"),
        SyllabusTopicNode("P5_CH10_BNK", CAInterSubject.PAPER_5, "Ch 10: Audit of Banks", "Bank Audit Approach, Advances & NPA Special Consideration"),
        SyllabusTopicNode("P5_CH11_ETH", CAInterSubject.PAPER_5, "Ch 11: Ethics & Terms of Engagement", "Professional Ethics, Independence Threats, SQC 1, SA 210 & SA 220"),

        // ================= PAPER 6A: FINANCIAL MANAGEMENT (50M) =================
        SyllabusTopicNode("P6A_CH1_OVW", CAInterSubject.PAPER_6A, "Ch 1: FM & Financial Analysis", "FM Functions, Objectives, Value Creation & Financial Distress"),
        SyllabusTopicNode("P6A_CH1_RAT", CAInterSubject.PAPER_6A, "Ch 1: FM & Financial Analysis", "Financial Analysis through Ratios"),
        SyllabusTopicNode("P6A_CH2_SRC", CAInterSubject.PAPER_6A, "Ch 2: Financing Decisions & Cost of Capital", "Sources of Finance (Long term, Short term, Contemporary, P2P, Lease)"),
        SyllabusTopicNode("P6A_CH2_COC", CAInterSubject.PAPER_6A, "Ch 2: Financing Decisions & Cost of Capital", "Cost of Capital (WACC & Marginal Cost of Capital)"),
        SyllabusTopicNode("P6A_CH2_CAP", CAInterSubject.PAPER_6A, "Ch 2: Financing Decisions & Cost of Capital", "Capital Structure Theories (EBIT-EPS Analysis, Relevancy/Irrelevancy)"),
        SyllabusTopicNode("P6A_CH2_LEV", CAInterSubject.PAPER_6A, "Ch 2: Financing Decisions & Cost of Capital", "Leverages (Operating, Financial and Combined)"),
        SyllabusTopicNode("P6A_CH3_INV", CAInterSubject.PAPER_6A, "Ch 3: Capital Investment & Dividend", "Capital Budgeting (Payback, ARR, NPV, IRR, MIRR, Profitability Index)"),
        SyllabusTopicNode("P6A_CH3_DIV", CAInterSubject.PAPER_6A, "Ch 3: Capital Investment & Dividend", "Dividend Decisions (Walter's, Gordon's & MM Hypothesis)"),
        SyllabusTopicNode("P6A_CH4_WC", CAInterSubject.PAPER_6A, "Ch 4: Management of Working Capital", "Working Capital Cycle, Receivables, Payables, Cash & Factoring"),

        // ================= PAPER 6B: STRATEGIC MANAGEMENT (50M) =================
        SyllabusTopicNode("P6B_CH1_INT", CAInterSubject.PAPER_6B, "Ch 1: Introduction to Strategic Management", "Strategic Intent (Vision, Mission, Goals) & Strategic Levels"),
        SyllabusTopicNode("P6B_CH2_EXT", CAInterSubject.PAPER_6B, "Ch 2: External Environment Analysis", "PESTLE, Porter's 5 Forces & Industry Environment Analysis"),
        SyllabusTopicNode("P6B_CH3_INT", CAInterSubject.PAPER_6B, "Ch 3: Internal Environment Analysis", "Mendelow's Model, SWOT & Porter's Generic Competitive Strategies"),
        SyllabusTopicNode("P6B_CH4_CHC", CAInterSubject.PAPER_6B, "Ch 4: Strategic Choices", "Ansoff, BCG, ADL, GE Matrix, Turnaround, Divestiture & Liquidation"),
        SyllabusTopicNode("P6B_CH5_IMP", CAInterSubject.PAPER_6B, "Ch 5: Strategy Implementation & Evaluation", "Digital Transformation, Org Culture, Leadership & Performance Controls")
    )

    fun getChaptersForSubject(subject: CAInterSubject): List<String> {
        return allTopics.filter { it.subject == subject }.map { it.chapterName }.distinct()
    }

    fun getSubTopicsForChapter(subject: CAInterSubject, chapterName: String): List<SyllabusTopicNode> {
        return allTopics.filter { it.subject == subject && it.chapterName == chapterName }
    }
}

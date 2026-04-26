export default function TermsPage() {
  return (
    <div className="flex flex-col gap-6 px-4 pb-8">
      <h1 className="text-2xl font-bold">Terms of Service</h1>
      <div className="space-y-4 text-sm text-muted-foreground">
        <section>
          <h2 className="mb-2 text-lg font-semibold text-foreground">1. Acceptance</h2>
          <p>By using VisionStudio AI, you agree to these Terms of Service. If you do not agree, please do not use our services.</p>
        </section>
        <section>
          <h2 className="mb-2 text-lg font-semibold text-foreground">2. Eligibility</h2>
          <p>You must be at least 13 years old to use VisionStudio AI. Mature content features require you to be 18+.</p>
        </section>
        <section>
          <h2 className="mb-2 text-lg font-semibold text-foreground">3. Content Policy</h2>
          <p>You may not use VisionStudio AI to generate, edit, or animate:</p>
          <ul className="mt-2 list-disc pl-5">
            <li>Illegal content</li>
            <li>Content involving minors in sexual or violent contexts</li>
            <li>Non-consensual intimate imagery</li>
            <li>Content intended to deceive or defraud</li>
            <li>Hate speech or harassment</li>
          </ul>
        </section>
        <section>
          <h2 className="mb-2 text-lg font-semibold text-foreground">4. Intellectual Property</h2>
          <p>You retain rights to content you create using VisionStudio AI. We do not claim ownership of your creations.</p>
        </section>
        <section>
          <h2 className="mb-2 text-lg font-semibold text-foreground">5. Service Availability</h2>
          <p>VisionStudio AI is provided "as is" without warranties. We reserve the right to modify or discontinue services.</p>
        </section>
      </div>
    </div>
  );
}

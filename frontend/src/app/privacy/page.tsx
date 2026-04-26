export default function PrivacyPage() {
  return (
    <div className="flex flex-col gap-6 px-4 pb-8">
      <h1 className="text-2xl font-bold">Privacy Policy</h1>
      <div className="space-y-4 text-sm text-muted-foreground">
        <section>
          <h2 className="mb-2 text-lg font-semibold text-foreground">1. Information We Collect</h2>
          <p>We collect:</p>
          <ul className="mt-2 list-disc pl-5">
            <li>Account information (email, name)</li>
            <li>Generated content and prompts</li>
            <li>Device information for PWA functionality</li>
            <li>Usage analytics (anonymized)</li>
          </ul>
        </section>
        <section>
          <h2 className="mb-2 text-lg font-semibold text-foreground">2. How We Use Data</h2>
          <p>We use your data to:</p>
          <ul className="mt-2 list-disc pl-5">
            <li>Provide and improve our services</li>
            <li>Process your AI generation requests</li>
            <li>Send notifications about your jobs</li>
            <li>Ensure platform safety and compliance</li>
          </ul>
        </section>
        <section>
          <h2 className="mb-2 text-lg font-semibold text-foreground">3. Data Storage</h2>
          <p>Your data is stored securely using industry-standard encryption. Generated images are stored for 90 days unless you delete them.</p>
        </section>
        <section>
          <h2 className="mb-2 text-lg font-semibold text-foreground">4. Your Rights</h2>
          <p>You can request deletion of your account and data at any time. Contact us at privacy@visionstudio.app.</p>
        </section>
        <section>
          <h2 className="mb-2 text-lg font-semibold text-foreground">5. Cookies</h2>
          <p>We use essential cookies for authentication and PWA functionality. We do not use tracking cookies.</p>
        </section>
      </div>
    </div>
  );
}

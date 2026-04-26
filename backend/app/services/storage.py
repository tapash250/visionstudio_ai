import boto3
import os
from typing import Optional

class StorageService:
    def __init__(self):
        provider = os.getenv("STORAGE_PROVIDER", "r2")

        if provider == "r2":
            self.client = boto3.client(
                "s3",
                endpoint_url=f"https://{os.getenv('R2_ACCOUNT_ID')}.r2.cloudflarestorage.com",
                aws_access_key_id=os.getenv("R2_ACCESS_KEY_ID"),
                aws_secret_access_key=os.getenv("R2_SECRET_ACCESS_KEY"),
            )
            self.bucket = os.getenv("R2_BUCKET_NAME", "visionstudio-media")
            self.public_url = os.getenv("R2_PUBLIC_URL", "")
        else:
            self.client = boto3.client("s3")
            self.bucket = os.getenv("S3_BUCKET_NAME", "visionstudio-media")
            self.public_url = ""

    async def upload(self, key: str, data: bytes, content_type: str) -> str:
        self.client.put_object(
            Bucket=self.bucket,
            Key=key,
            Body=data,
            ContentType=content_type,
        )

        if self.public_url:
            return f"{self.public_url}/{key}"

        # Generate presigned URL
        url = self.client.generate_presigned_url(
            "get_object",
            Params={"Bucket": self.bucket, "Key": key},
            ExpiresIn=3600,
        )
        return url

    async def delete(self, key: str) -> bool:
        try:
            self.client.delete_object(Bucket=self.bucket, Key=key)
            return True
        except Exception:
            return False

    async def get_signed_url(self, key: str, expiry: int = 3600) -> str:
        return self.client.generate_presigned_url(
            "get_object",
            Params={"Bucket": self.bucket, "Key": key},
            ExpiresIn=expiry,
        )
